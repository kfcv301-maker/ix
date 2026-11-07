import { mountManager } from './manager.js?v=1.1.0';

const base = '/api/v1/node-address-sync/';
const routePath = '/server/manage';
const flag = 'node_address_sync';
let active = null, scheduled = false, loading = false;

export function readAdminToken() {
    try {
        // This key and expiry format match admin-dist ef5f43d used by Xboard 8e4864b.
        const stored = JSON.parse(localStorage.getItem('XBOARD_ACCESS_TOKEN') || 'null');
        if (!stored || typeof stored.value !== 'string' || !stored.value
            || (stored.expire != null && Number(stored.expire) <= Date.now())) return '';
        return stored.value;
    } catch { return ''; }
}

function currentRoute() {
    const hash = location.hash.replace(/^#/, '') || '/';
    return new URL(hash, location.origin);
}

function navigate(open) {
    const current = currentRoute();
    const query = current.pathname === routePath ? current.searchParams : new URLSearchParams();
    if (open) query.set(flag, '1'); else query.delete(flag);
    const suffix = query.toString();
    location.hash = routePath + (suffix ? '?' + suffix : '');
    schedule();
}

function clearPage() {
    if (!active) return;
    active.dispose?.();
    for (const [node, previous] of active.hidden) {
        if (previous == null) node.removeAttribute('data-node-address-sync-hidden');
        else node.setAttribute('data-node-address-sync-hidden', previous);
    }
    active.host.remove();
    active = null;
}

function hideOriginal(main) {
    for (const node of main.children) {
        if (node === active.host || node.tagName === 'STYLE') continue;
        if (!active.hidden.has(node)) {
            active.hidden.set(node, node.getAttribute('data-node-address-sync-hidden'));
            node.setAttribute('data-node-address-sync-hidden', '');
        }
    }
}

function entryButton() {
    const button = document.createElement('button');
    button.id = 'node-address-sync-entry';
    button.type = 'button';
    button.className = 'inline-flex items-center justify-center whitespace-nowrap rounded-md text-sm font-medium h-9 px-4 py-2 bg-primary text-primary-foreground shadow hover:bg-primary/90';
    button.textContent = 'IP 自动同步';
    button.addEventListener('click', () => navigate(true));
    return button;
}

function addSidebarEntry() {
    const anchor = [...document.querySelectorAll('a[href]')].find(a => {
        const hash = new URL(a.href, location.origin).hash.slice(1);
        return hash.split('?')[0] === routePath && !a.closest('#node-address-sync-host');
    });
    const old = document.getElementById('node-address-sync-sidebar');
    if (!anchor || old) return;
    const link = document.createElement('a');
    link.id = 'node-address-sync-sidebar';
    link.className = anchor.className;
    link.textContent = 'IP 自动同步';
    link.href = '#' + routePath + '?' + flag + '=1';
    link.addEventListener('click', event => { event.preventDefault(); navigate(true); });
    anchor.after(link);
}

async function showPage(main) {
    if (active?.main === main) { hideOriginal(main); return; }
    if (loading) return;
    clearPage();
    loading = true;
    try {
        const token = readAdminToken();
        if (!token) return;
        const response = await fetch(base + 'ui', {headers:{'Authorization':token, 'Accept':'text/html'}, cache:'no-store'});
        if (!response.ok) {
            throw Error(response.status === 401 || response.status === 403
                ? '当前登录已失效或没有管理员权限，请在后台重新登录。' : '无法打开 IP 自动同步页面，请刷新后台后重试。');
        }
        const html = await response.text();
        // Requests can finish after navigation or a logout; do not resurrect the page.
        if (!readAdminToken() || currentRoute().pathname !== routePath
            || currentRoute().searchParams.get(flag) !== '1' || !main.isConnected) return;
        const host = document.createElement('div');
        host.id = 'node-address-sync-host';
        host.style.cssText = 'height:100%;overflow:auto;position:relative;';
        const shadow = host.attachShadow({mode:'open'});
        shadow.innerHTML = html;
        active = {host, main, hidden:new Map(), dispose:null};
        main.append(host);
        hideOriginal(main);
        active.dispose = mountManager(shadow, readAdminToken, () => navigate(false));
    } catch (error) {
        if (currentRoute().searchParams.get(flag) === '1') {
            alert(error.message);
            navigate(false);
        }
    } finally { loading = false; }
}

function update() {
    if (!readAdminToken()) {
        clearPage();
        document.getElementById('node-address-sync-entry')?.remove();
        document.getElementById('node-address-sync-sidebar')?.remove();
        return;
    }
    addSidebarEntry();
    const route = currentRoute();
    const main = document.querySelector('main#content');
    const isNodePage = route.pathname === routePath;
    if (!isNodePage || !main) {
        clearPage();
        document.getElementById('node-address-sync-entry')?.remove();
        return;
    }
    if (route.searchParams.get(flag) === '1') {
        showPage(main);
        return;
    }
    clearPage();
    if (!document.getElementById('node-address-sync-entry')) {
        const heading = main.querySelector('h2, h1');
        if (heading) {
            const header = heading.parentElement?.parentElement;
            (header || heading.parentElement).append(entryButton());
        }
    }
}

function schedule() {
    if (scheduled) return;
    scheduled = true;
    requestAnimationFrame(() => { scheduled = false; update(); });
}

const styles = document.createElement('style');
styles.textContent = 'main#content>[data-node-address-sync-hidden]{display:none!important}#node-address-sync-entry{margin-left:12px;flex-shrink:0}#node-address-sync-sidebar{display:flex;align-items:center;min-height:36px;padding-left:32px}';
document.head.append(styles);
new MutationObserver(schedule).observe(document.getElementById('root') || document.body, {childList:true,subtree:true});
window.addEventListener('hashchange', schedule);
window.addEventListener('popstate', schedule);
window.addEventListener('storage', event => { if (event.key === 'XBOARD_ACCESS_TOKEN' || event.key === null) schedule(); });
schedule();
