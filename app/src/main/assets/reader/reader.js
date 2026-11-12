// Application bridge. Vendored foliate-js files are intentionally unmodified.
const ORIGIN = 'https://appassets.androidplatform.net'
const DEFAULTS = {
    fontSize: 20, lineHeight: 1.8, theme: 'paper', scroll: false,
    serif: true, keepScreenOn: true,
}
const COLORS = {
    paper: ['#F6F1E7', '#24211D', '#78542B'],
    white: ['#FFFFFF', '#202020', '#305F99'],
    night: ['#171717', '#DEDAD2', '#A9C8EF'],
}
let session, engine, view, book, controller, latest, fault, retryTarget
let settings = { ...DEFAULTS }
let generation = 0, serial = Promise.resolve(), turnQueued = false
let working = false, ready = false, pendingLoaded = false, touchUntil = 0
let activeTarget, layoutObserver

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
        toc: rows.map(row => ({ label: row.title, href: row.url })),
        resolveHref(href) {
            const [url, fragment] = parse(href)
            const index = indices.get(url)
            if (index === undefined) throw new Error('无效的章节链接')
            return { index, anchor: doc => fragment ? doc.getElementById(fragment) ?? 0 : 0 }
        },
        splitTOCHref: href => parse(href),
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
    for (const key of ['scroll', 'serif', 'keepScreenOn'])
        if (typeof patch?.[key] === 'boolean') next[key] = patch[key]
    return next
}
function updateGap() {
    if (!view || view.isFixedLayout) return
    const width = view.getBoundingClientRect().width
    if (width > 48) view.renderer.setAttribute('gap',
        `${2400 / (settings.scroll ? width + 24 : width)}%`)
}
function applySettings() {
    if (!view || view.isFixedLayout) return
    const [background, foreground, link] = COLORS[settings.theme]
    const renderer = view.renderer
    document.documentElement.style.background = background
    document.body.style.background = background
    renderer.setAttribute('flow', settings.scroll ? 'scrolled' : 'paginated')
    renderer.setAttribute('margin', '24px')
    updateGap()
    renderer.setAttribute('max-inline-size', '100000px')
    renderer.setAttribute('max-block-size', '100000px')
    renderer.setAttribute('max-column-count', '1')
    // Foliate applies this stylesheet to subsequent sections too.
    renderer.setStyles(`
        html { font-size: ${settings.fontSize}px !important;
            color: ${foreground} !important; background: ${background} !important;
            color-scheme: ${settings.theme === 'night' ? 'dark' : 'light'}; }
        body { color: inherit !important; background: transparent !important;
            font-family: ${settings.serif ? 'serif' : 'sans-serif'} !important;
            padding: 0 !important; box-sizing: border-box !important;
            line-height: ${settings.lineHeight} !important; }
        p, li, blockquote, dd { line-height: ${settings.lineHeight} !important; }
        p { text-indent: 2em; margin-block: 0 .6em; }
        a { color: ${link} !important; }
        pre { white-space: pre-wrap !important; }
    `)
}

function flatten(items = []) {
    return (items || []).flatMap(item => [
        ...(typeof item.href === 'string' && item.href ? [{ label: String(item.label || ''), href: item.href }] : []),
        ...flatten(item.subitems || []),
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
    })
}

function bindDocument(doc, owner, token) {
    let down, clickTimer
    const selection = () => !doc.getSelection()?.isCollapsed
    const cancel = () => { clearTimeout(clickTimer); down = null }
    doc.addEventListener('pointerdown', event => {
        clearTimeout(clickTimer)
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
    doc.addEventListener('selectionchange', () => { if (selection()) clearTimeout(clickTimer) })
    doc.addEventListener('dblclick', cancel)
    doc.addEventListener('click', event => {
        clearTimeout(clickTimer)
        const element = event.target.nodeType === 1 ? event.target : event.target.parentElement
        if (!down || down.moved || down.selected || selection() || event.detail > 1
            || performance.now() - down.time > 350 || event.defaultPrevented
            || element?.closest('a, button, input, textarea, select, label, audio, video, [contenteditable]')) return
        const frame = doc.defaultView.frameElement
        const rect = frame.getBoundingClientRect()
        const bounds = owner.getBoundingClientRect()
        const x = event.clientX * rect.width / frame.clientWidth + rect.left - bounds.left
        const ratio = x / bounds.width
        down = null
        clickTimer = setTimeout(() => {
            if (token !== generation || fault || selection()) return
            if (ratio < .3) commands({ type: book.dir === 'rtl' ? 'next' : 'prev' })
            else if (ratio > .7) commands({ type: book.dir === 'rtl' ? 'prev' : 'next' })
            else send({ type: 'tap' })
        }, 240)
    })
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
    view = book = latest = undefined
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
        if (token !== generation || fault) return
        latest = detail
        if (!working) publish()
    })
    document.body.append(owner)
    await owner.open(book)
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
        settings = cleanSettings(command.settings)
        if (!fault) applySettings()
        return
    }
    if (command.type === 'retry') {
        await boot(retryTarget)
        return
    }
    if (fault || !ready) return
    await sleep(Math.max(0, touchUntil - performance.now()))
    if (command.type === 'goto') {
        if (typeof command.href !== 'string' || !command.href) throw new Error('无效的章节链接')
        await go(command.href)
    } else if (command.type === 'fraction') {
        if (!Number.isFinite(command.fraction)) throw new Error('无效的阅读进度')
        await go({ fraction: Math.max(0, Math.min(1, command.fraction)) })
    } else {
        activeTarget = undefined
        retryTarget = latest?.cfi ?? 0
        await deadline(command.type === 'next' ? view.next() : view.prev())
        if (fault) throw fault
    }
    publish()
}
function commands(command) {
    if (!command || !['next', 'prev', 'goto', 'fraction', 'settings', 'retry'].includes(command.type))
        return Promise.resolve()
    const turn = command.type === 'next' || command.type === 'prev'
    if (turn && turnQueued) return serial
    if (turn) turnQueued = true
    serial = serial.then(async () => {
        working = true
        try { await execute(command) } catch (error) { fail(error) }
        finally {
            activeTarget = undefined
            working = false
            if (turn) turnQueued = false
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
