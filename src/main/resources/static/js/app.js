/* 공용 헬퍼 — 두 화면이 함께 쓴다. 전역 App 하나만 노출한다. */
(function () {
    'use strict';

    var App = {};

    App.el = function (id) { return document.getElementById(id); };

    /** 파일명 등 사용자 유래 문자열은 textContent 로만 넣는다 (XSS 방지, 명세 7장) */
    App.text = function (node, value) { node.textContent = value == null ? '' : String(value); return node; };

    App.create = function (tag, className, textValue) {
        var node = document.createElement(tag);
        if (className) { node.className = className; }
        if (textValue != null) { node.textContent = String(textValue); }
        return node;
    };

    App.setMsg = function (node, message, kind) {
        node.className = 'msg' + (kind ? ' ' + kind : '');
        App.text(node, message || '');
    };

    /** JSON API 호출. 2xx 가 아니면 { code, message, status } 를 가진 Error 를 던진다 */
    App.api = async function (url, options) {
        var res = await fetch(url, options);
        if (res.status === 204) { return null; }
        var body = null;
        try { body = await res.json(); } catch (e) { /* 본문 없음 */ }
        if (!res.ok) {
            var err = new Error(body && body.message ? body.message : '요청에 실패했습니다. (HTTP ' + res.status + ')');
            err.code = body && body.code;
            err.status = res.status;
            throw err;
        }
        return body;
    };

    App.formatBytes = function (bytes) {
        if (bytes == null) { return ''; }
        if (bytes < 1024) { return bytes + ' B'; }
        var units = ['KB', 'MB', 'GB'];
        var value = bytes / 1024, i = 0;
        while (value >= 1024 && i < units.length - 1) { value /= 1024; i++; }
        return (value >= 10 ? Math.round(value) : Math.round(value * 10) / 10) + ' ' + units[i];
    };

    /** 1시간 안이면 상대 시각, 그 밖은 "10월 1일 14:32" */
    App.formatTime = function (iso) {
        if (!iso) { return ''; }
        var date = new Date(iso);
        var diff = (Date.now() - date.getTime()) / 1000;
        if (diff < 60) { return '방금 전'; }
        if (diff < 3600) { return Math.floor(diff / 60) + '분 전'; }
        var sameYear = date.getFullYear() === new Date().getFullYear();
        var opts = { month: 'long', day: 'numeric', hour: '2-digit', minute: '2-digit', hour12: false };
        if (!sameYear) { opts.year = 'numeric'; }
        return date.toLocaleString('ko-KR', opts);
    };

    App.formatDateTime = function (iso) {
        if (!iso) { return ''; }
        return new Date(iso).toLocaleString('ko-KR', { month: 'numeric', day: 'numeric', hour: '2-digit', minute: '2-digit', hour12: false });
    };

    /** SVG 아이콘 (경로만 바뀜). 사용자 입력이 섞이지 않으므로 innerHTML 사용 가능 */
    var ICONS = {
        check: '<path d="M20 6 9 17l-5-5"/>',
        x: '<path d="M18 6 6 18M6 6l12 12"/>',
        upload: '<path d="M12 16V4m0 0-4 4m4-4 4 4"/><path d="M4 16v3a1 1 0 0 0 1 1h14a1 1 0 0 0 1-1v-3"/>',
        download: '<path d="M12 4v12m0 0-4-4m4 4 4-4"/><path d="M4 16v3a1 1 0 0 0 1 1h14a1 1 0 0 0 1-1v-3"/>',
        eye: '<path d="M2 12s3.5-7 10-7 10 7 10 7-3.5 7-10 7S2 12 2 12Z"/><circle cx="12" cy="12" r="3"/>',
        refresh: '<path d="M21 12a9 9 0 1 1-2.6-6.4"/><path d="M21 4v5h-5"/>',
        shield: '<path d="M12 3 4 6v6c0 5 3.5 8.5 8 9 4.5-.5 8-4 8-9V6l-8-3Z"/>',
        file: '<path d="M14 3H7a2 2 0 0 0-2 2v14a2 2 0 0 0 2 2h10a2 2 0 0 0 2-2V8l-5-5Z"/><path d="M14 3v5h5"/>'
    };
    App.icon = function (name) {
        var svg = document.createElementNS('http://www.w3.org/2000/svg', 'svg');
        svg.setAttribute('viewBox', '0 0 24 24');
        svg.setAttribute('fill', 'none');
        svg.setAttribute('stroke', 'currentColor');
        svg.setAttribute('stroke-width', '2.2');
        svg.setAttribute('stroke-linecap', 'round');
        svg.setAttribute('stroke-linejoin', 'round');
        svg.setAttribute('aria-hidden', 'true');
        svg.innerHTML = ICONS[name] || '';
        return svg;
    };

    App.pill = function (kind, label, iconName) {
        var pill = App.create('span', 'pill ' + kind);
        if (iconName) { pill.appendChild(App.icon(iconName)); }
        pill.appendChild(document.createTextNode(label));
        return pill;
    };

    /** 아래쪽 토스트. 사용자 문자열은 textContent */
    App.toast = function (message, kind) {
        var host = document.querySelector('.toast-host');
        if (!host) { host = App.create('div', 'toast-host'); document.body.appendChild(host); }
        var toast = App.create('div', 'toast' + (kind ? ' ' + kind : ''), message);
        toast.setAttribute('role', 'status');
        host.appendChild(toast);
        setTimeout(function () { toast.remove(); }, 3200);
    };

    window.App = App;
})();
