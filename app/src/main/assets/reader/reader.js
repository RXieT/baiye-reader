// Application bridge. Vendored foliate-js files are intentionally unmodified.
const ORIGIN = 'https://appassets.androidplatform.net'
const DEFAULTS = {
    fontSize: 20, lineHeight: 1.8, theme: 'paper', scroll: false,
    serif: true, keepScreenOn: true, fontWeight: 400, paragraphSpacing: .6,
    pageMargin: 24, brightness: -1, volumeKeys: false, animatedPage: true,
    showReadingStatus: true, immersive: true,
    turnAnimation: 'slide', fontMode: 'system', fontFile: '', fontLabel: '',
    background: 'none', backgroundPresets: [],
}
const COLORS = {
    paper: ['#F6F1E7', '#24211D', '#78542B'],
    white: ['#FFFFFF', '#202020', '#305F99'],
    green: ['#E3EDDF', '#243427', '#386347'],
    night: ['#171717', '#DEDAD2', '#A9C8EF'],
}
let session, engine, view, book, controller, latest, fault, retryTarget
let settings = { ...DEFAULTS }
let generation = 0, serial = Promise.resolve(), turnQueued = false
let working = false, ready = false, pendingLoaded = false, touchUntil = 0
let activeTarget, layoutObserver, pagination, pendingPage, pageDrain
let styleKey, lastTap
const layoutKeys = ['fontSize', 'fontWeight', 'lineHeight', 'paragraphSpacing',
    'pageMargin', 'scroll', 'fontMode', 'fontFile']

function send(message) {
    if (window.top !== window) return
    if (window.Native?.postMessage) window.Native.postMessage(JSON.stringify(message))
    else (window.__messages ??= []).push(message)
}

function fail(error, target = retryTarget) {
    const message = error?.message || String(error || '阅读内核加载失败')
    retryTarget = target
    if (fault?.message === message) return
    fault = error instanceof Error ? error : new Error(message)
    send({ type: 'error', message })
}
window.addEventListener('unhandledrejection', event => {
    event.preventDefault()
    fail(event.reason)
})
window.addEventListener('error', event => {
    if (event.error || event.target?.tagName === 'SCRIPT')
        fail(event.error || new Error('阅读内核模块加载失败'))
}, true)

function requireFeatures() {
    try {
        if (location.origin !== ORIGIN || !window.isSecureContext
            || !Object.groupBy || !Map.groupBy || !window.customElements
            || !window.ResizeObserver || !window.WeakRef || !Intl.Locale
            || !crypto.subtle || !CSS.supports('container-type', 'size')
            || !CSS.supports('selector(&)')) throw new Error()
        new DecompressionStream('deflate-raw')
    } catch {
        throw new Error('请更新 WebView')
    }
}

const sleep = ms => new Promise(resolve => setTimeout(resolve, ms))
async function deadline(promise, ms = 45000) {
    let timer
    try {
        return await Promise.race([promise, new Promise((_, reject) => {
            timer = setTimeout(() => reject(new Error('章节加载超时，请重试')), ms)
        })])
    } finally { clearTimeout(timer) }
}
async function response(path, signal) {
    const request = new AbortController()
    const cancel = () => request.abort()
    signal.addEventListener('abort', cancel, { once: true })
    if (signal.aborted) request.abort()
    const timer = setTimeout(cancel, 30000)
    try {
        const res = await fetch(path, {
            signal: request.signal, redirect: 'error', credentials: 'omit',
        })
        if (!res.ok) throw new Error(`加载失败 (${res.status})，请重试`)
        // Consume within the timeout, not after returning the response headers.
        return new Blob([await res.arrayBuffer()], {
            type: res.headers.get('content-type') || 'application/octet-stream',
        })
    } finally {
        clearTimeout(timer)
        signal.removeEventListener('abort', cancel)
    }
}

// Defense in depth, not a replacement for native sanitization/network isolation.
function protectMarkup(text, type = 'application/xhtml+xml') {
    const doc = new DOMParser().parseFromString(text, type)
    if (doc.querySelector('parsererror') || (type !== 'image/svg+xml' && !doc.body))
        throw new Error('章节 XHTML 无效')
    for (const el of doc.querySelectorAll('script, iframe, object, embed, base')) el.remove()
    for (const el of doc.querySelectorAll('*')) {
        if (el.localName === 'meta' && /refresh/i.test(el.getAttribute('http-equiv') || '')) {
            el.remove()
            continue
        }
        for (const attr of [...el.attributes]) {
            if (/^on/i.test(attr.localName) || attr.localName === 'srcdoc'
                || /^(javascript|vbscript):/i.test(attr.value.replace(/[\u0000-\u0020]/g, '')))
                el.removeAttributeNode(attr)
        }
    }
    if (doc.head) {
        const meta = doc.createElementNS('http://www.w3.org/1999/xhtml', 'meta')
        meta.setAttribute('http-equiv', 'Content-Security-Policy')
        meta.setAttribute('content', "script-src 'none'; object-src 'none'; frame-src 'none'; base-uri 'none'; form-action 'none'")
        doc.head.prepend(meta)
    }
    return new XMLSerializer().serializeToString(doc)
}

function onlineBook(data, signal) {
    const base = `${ORIGIN}/chapter/${encodeURIComponent(String(data.book.id))}/`
    const rows = data.chapters.map(chapter => ({
        ...chapter, id: String(chapter.id),
        url: `${base}${encodeURIComponent(String(chapter.id))}.xhtml`,
    }))
    if (!rows.length) throw new Error('没有可阅读章节')
    const indices = new Map(rows.map((row, i) => [row.url, i]))
    if (indices.size !== rows.length) throw new Error('章节 ID 重复')
    const urls = new Map(), pending = new Map()
    const parse = href => {
        const url = new URL(href, base)
        const fragment = decodeURIComponent(url.hash.slice(1))
        url.hash = ''
        return [url.href, fragment]
    }
    // Keep contiguous volumes in source order (including repeated volume names).
    const toc = []
    let group
    for (const row of rows) {
        const label = String(row.volume || '').trim()
        if (!label) { group = undefined; toc.push({ label: row.title, href: row.url }); continue }
        if (!group || group.label !== label) {
            group = { label, subitems: [] }
            toc.push(group)
        }
        group.subitems.push({ label: row.title, href: row.url })
    }
    return {
        metadata: { title: data.book.title, author: data.book.author, language: 'zh-CN' },
        dir: 'ltr',
        sections: rows.map((row, index) => ({
            id: row.url, size: 1,
            load() {
                if (urls.has(index)) return urls.get(index)
                if (pending.has(index)) return pending.get(index)
                const promise = (async () => {
                    const blob = await response(row.url, signal)
                    const text = protectMarkup(await blob.text())
                    if (signal.aborted) throw new DOMException('Aborted', 'AbortError')
                    const url = URL.createObjectURL(new Blob([text], { type: 'application/xhtml+xml' }))
                    urls.set(index, url)
                    return url
                })().finally(() => pending.delete(index))
                pending.set(index, promise)
                return promise
            },
            unload() {
                if (urls.has(index)) URL.revokeObjectURL(urls.get(index))
                urls.delete(index)
            },
            resolveHref: href => new URL(href, row.url).href,
        })),
        toc,
        resolveHref(href) {
            const [url, fragment] = parse(href)
            const index = indices.get(url)
            if (index === undefined) throw new Error('无效的章节链接')
            return { index, anchor: doc => fragment ? doc.getElementById(fragment) ?? 0 : 0 }
        },
        splitTOCHref: href => href ? parse(href) : [],
        getTOCFragment: (doc, id) => id ? doc.getElementById(id) : doc.documentElement,
        isExternal(href) {
            try { return !indices.has(parse(href)[0]) } catch { return true }
        },
        destroy() {
            for (const url of urls.values()) URL.revokeObjectURL(url)
            urls.clear()
        },
    }
}

function cleanSettings(patch) {
    const next = { ...settings }
    if (Number.isFinite(patch?.fontSize)) next.fontSize = Math.max(12, Math.min(40, patch.fontSize))
    if (Number.isFinite(patch?.lineHeight)) next.lineHeight = Math.max(1.2, Math.min(3, patch.lineHeight))
    if (Object.hasOwn(COLORS, patch?.theme)) next.theme = patch.theme
    for (const [key, min, max] of [
        ['fontWeight', 100, 900], ['paragraphSpacing', 0, 3], ['pageMargin', 0, 64],
        ['brightness', -1, 1],
    ]) if (Number.isFinite(patch?.[key])) next[key] = Math.max(min, Math.min(max, patch[key]))
    next.fontWeight = Math.round(next.fontWeight / 100) * 100
    if (next.brightness < 0) next.brightness = -1
    for (const key of ['scroll', 'serif', 'keepScreenOn', 'volumeKeys', 'animatedPage', 'showReadingStatus', 'immersive'])
        if (typeof patch?.[key] === 'boolean') next[key] = patch[key]
    for (const [key, values] of Object.entries({
        turnAnimation: ['none', 'slide', 'curl'], fontMode: ['system', 'serif', 'sans', 'custom'],
    })) if (values.includes(patch?.[key])) next[key] = patch[key]
    if (typeof patch?.animatedPage === 'boolean' && !patch?.turnAnimation)
        next.turnAnimation = patch.animatedPage ? 'slide' : 'none'
    if (typeof patch?.serif === 'boolean' && !patch?.fontMode)
        next.fontMode = patch.serif ? 'serif' : 'system'
    for (const key of ['fontFile', 'fontLabel', 'background'])
        if (typeof patch?.[key] === 'string') next[key] = patch[key]
    if (Array.isArray(patch?.backgroundPresets)) next.backgroundPresets = patch.backgroundPresets
        .filter(p => p && typeof p.id === 'string' && typeof p.file === 'string')
        .map(p => ({ id: p.id, name: String(p.name || ''), file: p.file, dark: p.dark === true }))
    return next
}
function mediaURL(kind, file) {
    if (typeof file !== 'string' || !file || file === '.' || file === '..'
        || /[\/\\\u0000-\u001f\u007f]/.test(file)) return ''
    return `${ORIGIN}/media/${kind}/${encodeURIComponent(file)}`
}
function updateGap() {
    if (!view || view.isFixedLayout) return
    const width = view.getBoundingClientRect().width
    // Paginator already supplies the horizontal padding through gap. Do not
    // add body padding: in paginated flow its outer + half-column gap = margin.
    if (width > 0) {
        const margin = Math.min(settings.pageMargin, width / 4)
        const gap = `${margin * 100 / (settings.scroll ? width + margin : width)}%`
        if (view.renderer.getAttribute('gap') !== gap) view.renderer.setAttribute('gap', gap)
    }
}
function applySettings() {
    if (!view) return
    const [background, foreground, link] = COLORS[settings.theme]
    const renderer = view.renderer
    const textures = {
        paper: 'repeating-linear-gradient(17deg, #88663308 0 1px, transparent 1px 5px)',
        linen: 'repeating-linear-gradient(0deg, #8877550c 0 1px, transparent 1px 4px), repeating-linear-gradient(90deg, #8877550c 0 1px, transparent 1px 4px)',
        mist: 'radial-gradient(ellipse at 20% 15%, #a8b8c530, transparent 70%)',
    }
    const preset = settings.backgroundPresets.find(p => p.id === settings.background)
    const image = preset && mediaURL('background', preset.file)
    const rgb = background.match(/\w\w/g).map(x => parseInt(x, 16)).join(',')
    document.documentElement.style.background = background
    Object.assign(document.body.style, {
        backgroundColor: background,
        backgroundImage: image ? `linear-gradient(rgba(${rgb},${settings.theme === 'night' ? .78 : .72}), rgba(${rgb},${settings.theme === 'night' ? .78 : .72})), url("${image}")`
            : textures[settings.background] || 'none',
        backgroundSize: image ? 'cover' : 'auto', backgroundPosition: 'center',
    })
    // Paginator's closed-shadow background is a DOM part, not a canvas.
    let shellStyle = document.getElementById('reader-shell-style')
    if (!shellStyle) {
        shellStyle = document.createElement('style')
        shellStyle.id = 'reader-shell-style'
        document.head.append(shellStyle)
    }
    shellStyle.textContent = `foliate-view::part(filter) { background: transparent !important; }
        foliate-view { color: ${foreground}; --reader-link: ${link}; }`
    for (const { doc } of renderer.getContents()) paintDocument(doc)
    renderer.removeAttribute('animated')
    if (view.isFixedLayout) return
    const key = JSON.stringify(layoutKeys.map(k => settings[k]))
    if (styleKey === key) return
    styleKey = key
    const fontURL = settings.fontMode === 'custom' && mediaURL('font', settings.fontFile)
    const family = fontURL ? 'ReaderCustom' : settings.fontMode === 'serif' ? 'serif' : 'sans-serif'
    renderer.setAttribute('flow', settings.scroll ? 'scrolled' : 'paginated')
    renderer.setAttribute('margin', `${settings.pageMargin}px`)
    renderer.removeAttribute('animated')
    updateGap()
    renderer.setAttribute('max-inline-size', '100000px')
    renderer.setAttribute('max-block-size', '100000px')
    renderer.setAttribute('max-column-count', '1')
    // Foliate applies this stylesheet to subsequent sections too.
    renderer.setStyles(`
        ${fontURL ? `@font-face { font-family: ReaderCustom; src: url("${fontURL}"); font-display: swap; }` : ''}
        html { font-size: ${settings.fontSize}px !important; }
        html, body { background: transparent !important; }
        body, p, li, blockquote, dd, div, span, h1, h2, h3, h4, h5, h6 {
            font-family: ${family} !important; }
        body { color: inherit !important; background: transparent !important;
            font-weight: ${settings.fontWeight} !important;
            padding: 0 !important; box-sizing: border-box !important;
            line-height: ${settings.lineHeight} !important; }
        p, li, blockquote, dd { line-height: ${settings.lineHeight} !important; }
        p { text-indent: 2em; margin-block: 0 ${settings.paragraphSpacing}em !important; }
        pre { white-space: pre-wrap !important; }
        figure { margin: 0 auto !important; padding: 0 !important;
            text-indent: 0 !important; max-inline-size: 100% !important;
            break-inside: avoid !important; }
        img, figure > svg { display: block !important; margin-inline: auto !important;
            text-indent: 0 !important; max-inline-size: 100% !important;
            max-block-size: calc(100vh - ${settings.pageMargin * 2}px) !important;
            object-fit: contain !important; break-inside: avoid !important; }
        p:has(> img), a:has(> img) { text-indent: 0 !important; }
    `)
}
function paintDocument(doc) {
    const [background, foreground, link] = COLORS[settings.theme]
    let style = doc.getElementById('reader-paint')
    if (!style) {
        style = doc.createElement('style')
        style.id = 'reader-paint'
        doc.head.append(style)
    }
    style.textContent = `html, body { background: transparent !important; }
        html { color: ${foreground} !important; color-scheme: ${settings.theme === 'night' ? 'dark' : 'light'}; }
        a { color: ${link} !important; }`
}
async function restoreCFI(cfi, token = generation) {
    if (!cfi || token !== generation) return
    const renderer = view.renderer
    const docs = renderer.getContents().map(x => x.doc)
    await Promise.all(docs.map(doc => doc.fonts?.ready))
    await new Promise(resolve => requestAnimationFrame(() => requestAnimationFrame(resolve)))
    if (token === generation && !fault) await go(cfi)
}

function flatten(items = [], depth = 0) {
    return (items || []).flatMap(item => [
        { label: String(item.label || ''), href: typeof item.href === 'string' ? item.href : '', depth },
        ...flatten(item.subitems || [], depth + 1),
    ])
}
function publish() {
    if (!ready || fault || !latest) return
    const index = latest.section?.current ?? view.resolveCFI(latest.cfi)?.index
    const row = session.book.local ? null : session.chapters[index]
    const chapterId = row ? String(row.id) : String(book.sections[index]?.id ?? index)
    if (pendingLoaded) { send({ type: 'loaded' }); pendingLoaded = false }
    send({
        type: 'relocate', cfi: latest.cfi,
        fraction: Number.isFinite(latest.fraction) ? Math.max(0, Math.min(1, latest.fraction)) : 0,
        chapter: String(row?.title ?? latest.tocItem?.label ?? ''), chapterId,
        tocHref: String(latest.tocItem?.href ?? (row
            ? `${ORIGIN}/chapter/${encodeURIComponent(String(session.book.id))}/${encodeURIComponent(String(row.id))}.xhtml`
            : '')),
        sectionIndex: index, sectionCount: book.sections.length,
        // Raw paginator size is 1 / (pages - 2): the two sentinel blank
        // pages are NOT reading pages. View's location.total is an estimate.
        page: view.isFixedLayout ? index + 1 : pagination?.page ?? 0,
        pageCount: view.isFixedLayout ? book.sections.length : pagination?.pageCount ?? 0,
    })
}

function bindDocument(doc, owner, token) {
    paintDocument(doc)
    bindGestures(doc, token)
    let down
    const selection = () => !doc.getSelection()?.isCollapsed
    const cancel = () => { down = null }
    doc.addEventListener('pointerdown', event => {
        // First tap turns immediately. A second tap restores the original
        // anchor before selecting the original word; no artificial tap timer.
        const previous = lastTap
        if (previous && performance.now() - previous.time >= 350) lastTap = null
        if (previous && previous.token === token && performance.now() - previous.time < 350
            && Math.hypot(event.clientX - previous.x, event.clientY - previous.y) < 24) {
            previous.double = true
            commands({ type: 'restoreCFI', cfi: previous.cfi }).then(() => {
                let range = previous.range
                if (!range?.startContainer.isConnected && previous.wordCFI) {
                    const resolved = view.resolveCFI(previous.wordCFI)
                    const content = view.renderer.getContents().find(x => x.index === resolved?.index)
                    if (content) range = resolved.anchor(content.doc)
                }
                if (range?.startContainer.isConnected && token === generation) {
                    const selected = range.startContainer.ownerDocument.getSelection()
                    selected.removeAllRanges()
                    selected.addRange(range)
                }
            })
        }
        down = event.isPrimary && event.button === 0 ? {
            x: event.clientX, y: event.clientY, time: performance.now(),
            selected: selection(), moved: false,
        } : null
    }, { passive: true })
    doc.addEventListener('pointermove', event => {
        if (down && Math.hypot(event.clientX - down.x, event.clientY - down.y) > 10) {
            down.moved = true
            touchUntil = performance.now() + 400
        }
    }, { passive: true })
    doc.addEventListener('pointercancel', cancel, { passive: true })
    doc.addEventListener('dblclick', cancel)
    doc.addEventListener('click', event => {
        const element = event.target.nodeType === 1 ? event.target : event.target.parentElement
        if (!down || down.moved || down.selected || selection() || event.detail > 1 || lastTap?.double
            || performance.now() - down.time > 350 || event.defaultPrevented
            || element?.closest('a, button, input, textarea, select, label, audio, video, [contenteditable]')) return
        const frame = doc.defaultView.frameElement
        const rect = frame.getBoundingClientRect()
        const bounds = owner.getBoundingClientRect()
        const x = event.clientX * rect.width / frame.clientWidth + rect.left - bounds.left
        const ratio = x / bounds.width
        down = null
        if (token !== generation || selection()) return
        const range = doc.caretRangeFromPoint?.(event.clientX, event.clientY)
        if (range?.startContainer.nodeType === 3) {
            const node = range.startContainer, offset = range.startOffset
            const segment = [...new Intl.Segmenter(undefined, { granularity: 'word' }).segment(node.data)]
                .find(s => s.index <= offset && s.index + s.segment.length > offset)
            if (segment) { range.setStart(node, segment.index); range.setEnd(node, segment.index + segment.segment.length) }
        }
        const index = view.renderer.getContents().find(x => x.doc === doc)?.index
        const wordCFI = range && Number.isInteger(index) ? view.getCFI(index, range) : undefined
        lastTap = { token, time: performance.now(), x: event.clientX, y: event.clientY,
            cfi: latest?.cfi, range, wordCFI }
        if (ratio < .3) commands({ type: book.dir === 'rtl' ? 'next' : 'prev' })
        else if (ratio > .7) commands({ type: book.dir === 'rtl' ? 'prev' : 'next' })
        else send({ type: 'tap' })
    })
}
function bindGestures(target, token) {
    let gesture
    const selected = () => view?.renderer?.getContents().some(({ doc }) => !doc.getSelection()?.isCollapsed)
    const curl = () => settings.turnAnimation !== 'none' && !settings.scroll && !view?.isFixedLayout
    const opts = { capture: true, passive: false }
    target.addEventListener('touchstart', event => {
        const t = event.touches[0]
        gesture = event.touches.length === 1 ? { x: t.clientX, y: t.clientY,
            time: performance.now(), dx: 0, axis: null, blocked: selected() } : null
        // Keep all core touch state out of curl mode, including tiny moves and
        // touchend's unconditional snap. Do not prevent browser selection.
        if (curl()) event.stopImmediatePropagation()
    }, opts)
    target.addEventListener('touchmove', event => {
        if (curl()) event.stopImmediatePropagation()
        const g = gesture, t = event.touches[0]
        if (!g || !t || token !== generation) return
        if (event.touches.length !== 1 || selected()) {
            g.blocked = true
            if (selected()) event.stopImmediatePropagation()
            return
        }
        const dx = t.clientX - g.x, dy = t.clientY - g.y
        g.dx = dx
        if (!g.axis && Math.hypot(dx, dy) > 10) {
            g.axis = Math.abs(dx) > Math.abs(dy) * 1.3 ? 'x' : 'y'
            if (performance.now() - g.time > 350) g.blocked = true
            send({ type: 'interaction', kind: 'swipe' })
        }
        if (g.axis) touchUntil = performance.now() + (curl() ? 0 : 400)
        if (curl() && g.axis === 'x' && !g.blocked) event.preventDefault()
    }, opts)
    target.addEventListener('touchend', event => {
        const g = gesture
        gesture = null
        if (!curl()) return
        event.stopImmediatePropagation()
        if (!g || g.blocked || selected() || g.axis !== 'x' || Math.abs(g.dx) < 35) return
        event.preventDefault()
        commands({ type: (g.dx < 0) !== (book.dir === 'rtl') ? 'next' : 'prev' })
    }, opts)
    target.addEventListener('touchcancel', event => {
        gesture = null
        if (curl()) event.stopImmediatePropagation()
    }, opts)
    target.addEventListener('wheel', () => send({ type: 'interaction', kind: 'swipe' }), { passive: true })
    target.addEventListener('scroll', () => {
        if (!working) send({ type: 'interaction', kind: 'swipe' })
    }, { capture: true, passive: true })
}

function checkTarget(target) {
    const resolved = view.resolveNavigation(target)
    if (!Number.isInteger(resolved?.index) || resolved.index < 0 || resolved.index >= book.sections.length)
        throw new Error('无效的阅读位置')
    return resolved
}
function initialTarget() {
    if (session.book.local) {
        if (engine.CFI.isCFI.test(session.initialCfi || '')) {
            try { checkTarget(session.initialCfi); return session.initialCfi } catch { /* stale CFI */ }
        }
        return Math.max(0, book.sections.findIndex(section => section.linear !== 'no'))
    }
    const chapterIndex = id => session.chapters.findIndex(row => String(row.id) === String(id))
    if (session.startChapterId) {
        const index = chapterIndex(session.startChapterId)
        if (index < 0) throw new Error('指定章节不存在')
        return index
    }
    const index = session.initialChapterId ? chapterIndex(session.initialChapterId) : -1
    if (index >= 0 && engine.CFI.isCFI.test(session.initialCfi || '')) {
        try {
            if (checkTarget(session.initialCfi).index === index) return session.initialCfi
        } catch { /* Chapter IDs take precedence over stale/index-shifted CFIs. */ }
    }
    return index >= 0 ? index : 0
}
async function go(target) {
    activeTarget = retryTarget = target
    // View.goTo catches errors internally; renderer.goTo lets the bridge observe them.
    await deadline(view.renderer.goTo(checkTarget(target)))
    if (fault) throw fault
    if (!latest) throw new Error('未能定位正文，请重试')
}

async function boot(target) {
    ++generation
    controller?.abort()
    layoutObserver?.disconnect()
    try { view?.close() } catch { /* Upstream destroy may run before its first document. */ }
    view?.remove()
    book?.destroy?.()
    view = book = latest = pagination = styleKey = lastTap = pendingPage = undefined
    fault = undefined
    ready = pendingLoaded = false
    controller = new AbortController()
    const token = generation, signal = controller.signal
    requireFeatures()
    if (!engine) {
        const [module, CFI] = await Promise.all([import('./foliate-js/view.js'), import('./foliate-js/epubcfi.js')])
        engine = { makeBook: module.makeBook, CFI }
    }
    if (!session) {
        const data = JSON.parse(await (await response('/session.json', signal)).text())
        if (!data?.book || data.book.id == null || !Array.isArray(data.chapters)) throw new Error('阅读会话无效')
        session = data
        settings = cleanSettings(session.settings)
    }
    if (session.book.local) {
        const blob = await response('/book.epub', signal)
        const signature = new Uint8Array(await blob.slice(0, 4).arrayBuffer())
        if (signature[0] !== 0x50 || signature[1] !== 0x4b || signature[2] !== 3 || signature[3] !== 4)
            throw new Error('不是有效的 EPUB 文件')
        book = await engine.makeBook(new File([blob], 'book.epub', { type: 'application/epub+zip' }))
        book.transformTarget.addEventListener('load', ({ detail }) => {
            if (detail.isScript) detail.allow = false
        })
        book.transformTarget.addEventListener('data', ({ detail }) => {
            if (['application/xhtml+xml', 'text/html', 'image/svg+xml'].includes(detail.type))
                detail.data = Promise.resolve(detail.data).then(text => protectMarkup(text, detail.type))
        })
    } else book = onlineBook(session, signal)
    if (!book.sections.length) throw new Error('没有可阅读章节')
    // Also catches Foliate's gesture-driven loads, outside the command queue.
    book.sections.forEach((section, index) => {
        const load = section.load.bind(section)
        section.load = async () => {
            try { return await load() } catch (error) {
                if (token === generation) fail(error, activeTarget ?? index)
                throw error
            }
        }
    })
    const owner = view = document.createElement('foliate-view')
    owner.addEventListener('external-link', event => event.preventDefault())
    owner.addEventListener('link', event => {
        event.preventDefault()
        commands({ type: 'goto', href: event.detail.href })
    })
    owner.addEventListener('load', ({ detail: { doc } }) => {
        if (token !== generation) return
        pendingLoaded = true
        bindDocument(doc, owner, token)
    })
    owner.addEventListener('relocate', ({ detail }) => {
        if (token !== generation || fault || pagination?.padding) return
        latest = detail
        if (!working) publish()
    })
    document.body.append(owner)
    await owner.open(book)
    bindGestures(owner.renderer, token)
    // Capture at renderer target before View converts section fractions into
    // whole-book progress. No vendor patch and no estimated location counts.
    owner.renderer.addEventListener('relocate', ({ detail }) => {
        if (token !== generation) return
        const { fraction, size } = detail
        if (!view.isFixedLayout && !settings.scroll && Number.isFinite(size) && size > 0) {
            const pageCount = Math.max(1, Math.round(1 / size))
            const offset = Math.round(fraction / size)
            pagination = { index: detail.index, fraction, size, pageCount, page: Math.max(1, Math.min(pageCount, offset + 1)),
                padding: offset < 0 || offset >= pageCount }
        } else pagination = { page: 0, pageCount: 0, padding: false }
    }, { capture: true })
    layoutObserver = new ResizeObserver(updateGap)
    layoutObserver.observe(owner)
    applySettings()
    target = target ?? initialTarget()
    try { await go(target) } catch (error) {
        // A stale DOM CFI may have a valid section but an invalid text anchor.
        if (fault || !engine.CFI.isCFI.test(target)) throw error
        await go(checkTarget(target).index)
    }
    ready = true
    send({ type: 'ready', toc: flatten(book.toc), fixed: view.isFixedLayout })
    publish()
}

async function execute(command) {
    if (command.type === 'settings') {
        const next = cleanSettings(command.settings)
        const layoutChanged = layoutKeys.some(key => settings[key] !== next[key])
        const anchor = latest?.cfi
        settings = next
        if (!fault) {
            applySettings()
            if (layoutChanged && ready) await restoreCFI(anchor)
        }
        return
    }
    if (command.type === 'retry') {
        await boot(retryTarget)
        return
    }
    if (!ready) return
    if (fault) {
        // A failed next chapter must not trap the reader or block the menu.
        // Restore the last readable anchor before honoring a different action.
        const anchor = latest?.cfi
        if (!anchor) return
        fault = undefined
        await go(anchor)
        pendingLoaded = true
    }
    if (command.type === 'restoreCFI') {
        await go(command.cfi)
    } else if (command.type === 'page') {
        const index = latest?.section?.current ?? view.resolveCFI(latest.cfi)?.index
        // A drag belongs to the section where it began; never load another.
        if (view.isFixedLayout || settings.scroll || command.sectionIndex !== index
            || pagination?.index !== index || !(pagination.size > 0)) return
        const page = Math.max(1, Math.min(pagination.pageCount, command.page))
        const lastFraction = (pagination.pageCount - 1) * pagination.size
        // Relocate emits (page-1)/N, but numeric anchors use round(a*(N-1)).
        const anchor = lastFraction > 0 ? (page - 1) * pagination.size / lastFraction : 0
        send({ type: 'interaction', kind: 'turn', source: 'slider' })
        retryTarget = latest.cfi
        view.renderer.removeAttribute('animated')
        await deadline(view.renderer.goTo({ index, anchor }))
        if (fault) throw fault
    } else if (command.type === 'goto') {
        if (typeof command.href !== 'string' || !command.href) throw new Error('无效的章节链接')
        await go(command.href)
    } else if (command.type === 'section') {
        if (command.delta !== -1 && command.delta !== 1) return
        let index = (latest?.section?.current ?? view.resolveCFI(latest.cfi)?.index) + command.delta
        while (index >= 0 && index < book.sections.length && book.sections[index].linear === 'no')
            index += command.delta
        if (index >= 0 && index < book.sections.length) await go(index)
    } else if (command.type === 'fraction') {
        if (!Number.isFinite(command.fraction)) throw new Error('无效的阅读进度')
        await go({ fraction: Math.max(0, Math.min(1, command.fraction)) })
    } else {
        activeTarget = undefined
        retryTarget = latest?.cfi ?? 0
        send({ type: 'interaction', kind: 'turn' })
        const renderer = view.renderer
        const native = command.type === 'nativeTurn'
        if (native) renderer.removeAttribute('animated')
        try {
            await deadline((command.type === 'next' || (native && command.direction === 1))
                ? view.next() : view.prev())
        } finally {
            renderer.removeAttribute('animated')
        }
        if (fault) throw fault
    }
    publish()
}
function commands(command) {
    if (!command || !['next', 'prev', 'nativeTurn', 'page', 'restoreCFI', 'section', 'goto', 'fraction', 'settings', 'retry'].includes(command.type))
        return Promise.resolve()
    if (command.type === 'nativeTurn' && command.direction !== 1 && command.direction !== -1)
        return Promise.resolve()
    if ((command.type === 'next' || command.type === 'prev') && settings.turnAnimation !== 'none' && !settings.scroll) {
        send({ type: 'interaction', kind: 'turn' })
        send({ type: 'turn', direction: command.type === 'next' ? 1 : -1 })
        return Promise.resolve()
    }
    if (command.type === 'page') {
        if (!Number.isInteger(command.page) || (command.sectionIndex !== undefined
            && !Number.isInteger(command.sectionIndex))) return Promise.resolve()
        pendingPage = { ...command, sectionIndex: command.sectionIndex
            ?? latest?.section?.current ?? pagination?.index }
        if (pageDrain) return pageDrain
        // Exactly one queue slot; replace the pending target during every await.
        pageDrain = serial = serial.then(async () => {
            working = true
            try {
                while (pendingPage) {
                    const target = pendingPage
                    pendingPage = undefined
                    try { await execute(target) } catch (error) { fail(error) }
                }
            } finally {
                activeTarget = undefined
                working = false
                pageDrain = undefined
                publish()
            }
        })
        return pageDrain
    }
    const turn = command.type === 'next' || command.type === 'prev' || command.type === 'section'
    if (turn && turnQueued) return serial
    if (turn) turnQueued = true
    if (['section', 'goto', 'fraction'].includes(command.type))
        send({ type: 'interaction', kind: 'turn' })
    serial = serial.then(async () => {
        working = true
        const before = `${latest?.section?.current}:${pagination?.page}:${latest?.cfi}`
        try { await execute(command) } catch (error) { fail(error) }
        finally {
            activeTarget = undefined
            working = false
            if (turn) turnQueued = false
            if (command.type === 'nativeTurn') {
                const after = `${latest?.section?.current}:${pagination?.page}:${latest?.cfi}`
                send({ type: 'turnEnd', direction: command.direction, changed: before !== after && !fault })
            }
        }
    })
    return serial
}
window.Reader = Object.freeze({ commands })
if (window.top === window) {
    serial = serial.then(async () => {
        working = true
        try { await boot() } catch (error) { fail(error) }
        finally { activeTarget = undefined; working = false }
    })
}
