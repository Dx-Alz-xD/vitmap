package com.vitmap.app.feature.browser

/** JavaScript injected into pages for the element inspector. Talks back through `VitmapInspector`. */
object InspectorJs {
    /** Installs (once) the picker, the highlight overlay and helper functions on `window.__vm`. */
    val install = """
(function(){
  if (window.__vm) { return 'ok'; }
  var overlay = document.createElement('div');
  overlay.setAttribute('data-vitmap', '1');
  overlay.style.cssText = 'position:fixed;pointer-events:none;z-index:2147483647;border:2px solid #8b5cf6;background:rgba(139,92,246,0.18);border-radius:3px;transition:all .12s ease;display:none;box-shadow:0 0 0 9999px rgba(0,0,0,0.08)';
  var label = document.createElement('div');
  label.setAttribute('data-vitmap', '1');
  label.style.cssText = 'position:fixed;pointer-events:none;z-index:2147483647;background:#8b5cf6;color:#fff;font:600 11px monospace;padding:2px 6px;border-radius:4px;display:none;max-width:90vw;overflow:hidden;white-space:nowrap;text-overflow:ellipsis';
  document.documentElement.appendChild(overlay);
  document.documentElement.appendChild(label);
  var picking = false, current = null, nodes = [];
  function selectorOf(el){
    if (!el || el.nodeType !== 1) return '';
    if (el.id) return '#' + CSS.escape(el.id);
    var parts = [];
    while (el && el.nodeType === 1 && parts.length < 6) {
      var part = el.tagName.toLowerCase();
      if (el.id) { parts.unshift('#' + CSS.escape(el.id)); break; }
      var cls = (typeof el.className === 'string') ? el.className.trim().split(/\s+/).filter(Boolean).slice(0,2) : [];
      if (cls.length) part += '.' + cls.map(function(c){return CSS.escape(c);}).join('.');
      var p = el.parentElement;
      if (p) {
        var same = Array.prototype.filter.call(p.children, function(c){ return c.tagName === el.tagName; });
        if (same.length > 1) part += ':nth-of-type(' + (same.indexOf(el) + 1) + ')';
      }
      parts.unshift(part);
      el = p;
    }
    return parts.join(' > ');
  }
  var PROPS = ['display','position','width','height','margin','padding','color','background-color','font-family','font-size','font-weight','line-height','border','border-radius','box-shadow','opacity','z-index','flex-direction','justify-content','align-items','gap','overflow','transform'];
  function info(el){
    var r = el.getBoundingClientRect();
    var cs = getComputedStyle(el);
    var styles = {};
    PROPS.forEach(function(p){ styles[p] = cs.getPropertyValue(p); });
    var attrs = {};
    for (var i = 0; i < el.attributes.length; i++) { attrs[el.attributes[i].name] = el.attributes[i].value; }
    return JSON.stringify({
      selector: selectorOf(el), tag: el.tagName.toLowerCase(), id: el.id || '',
      classes: (typeof el.className === 'string') ? el.className : '',
      text: (el.innerText || '').slice(0, 400),
      html: el.outerHTML.slice(0, 6000),
      inline: el.getAttribute('style') || '',
      box: { x: Math.round(r.x), y: Math.round(r.y), w: Math.round(r.width), h: Math.round(r.height) },
      styles: styles, attrs: attrs,
      children: el.children.length
    });
  }
  function show(el){
    if (!el) { overlay.style.display = 'none'; label.style.display = 'none'; return; }
    var r = el.getBoundingClientRect();
    overlay.style.display = 'block';
    overlay.style.left = r.left + 'px'; overlay.style.top = r.top + 'px';
    overlay.style.width = r.width + 'px'; overlay.style.height = r.height + 'px';
    label.style.display = 'block';
    label.textContent = selectorOf(el) + '  ' + Math.round(r.width) + '×' + Math.round(r.height);
    label.style.left = Math.max(0, r.left) + 'px';
    label.style.top = (r.top > 22 ? r.top - 20 : r.bottom + 4) + 'px';
  }
  function select(el){
    if (!el || el.getAttribute && el.getAttribute('data-vitmap')) return;
    current = el; show(el);
    try { VitmapInspector.onSelect(info(el)); } catch (e) {}
  }
  function onTap(e){
    if (!picking) return;
    e.preventDefault(); e.stopPropagation();
    var t = e.changedTouches ? e.changedTouches[0] : e;
    overlay.style.display = 'none'; label.style.display = 'none';
    var el = document.elementFromPoint(t.clientX, t.clientY);
    select(el);
  }
  document.addEventListener('click', onTap, true);
  window.addEventListener('scroll', function(){ if (current) show(current); }, true);
  function tree(el, depth){
    var id = nodes.length; nodes.push(el);
    var o = { i: id, t: el.tagName.toLowerCase(), id: el.id || '', c: (typeof el.className === 'string') ? el.className.split(/\s+/).slice(0,3).join(' ') : '', n: el.children.length, k: [] };
    if (depth > 0) for (var j = 0; j < el.children.length && j < 60; j++) {
      var ch = el.children[j];
      if (ch.getAttribute('data-vitmap')) continue;
      o.k.push(tree(ch, depth - 1));
    }
    if (el.children.length === 0) o.x = (el.textContent || '').trim().slice(0, 60);
    return o;
  }
  window.__vm = {
    pick: function(on){ picking = on; if (!on) show(null); return 'ok'; },
    tree: function(depth){ nodes = []; return JSON.stringify(tree(document.documentElement, depth || 4)); },
    subtree: function(i, depth){ var el = nodes[i]; if (!el) return 'null'; return JSON.stringify(tree(el, depth || 3)); },
    selectNode: function(i){ select(nodes[i]); if (nodes[i]) nodes[i].scrollIntoView({block:'center', behavior:'smooth'}); return 'ok'; },
    parent: function(){ if (current && current.parentElement) select(current.parentElement); return 'ok'; },
    setStyle: function(css){ if (current) { current.setAttribute('style', css); select(current); } return 'ok'; },
    setAttr: function(name, value){ if (current) { if (value === null) current.removeAttribute(name); else current.setAttribute(name, value); select(current); } return 'ok'; },
    setText: function(t){ if (current) { current.textContent = t; select(current); } return 'ok'; },
    setHtml: function(h){ if (current) { var tmp = document.createElement('div'); tmp.innerHTML = h; var n = tmp.firstElementChild; if (n) { current.replaceWith(n); select(n); } } return 'ok'; },
    remove: function(){ if (current) { var p = current.parentElement; current.remove(); current = null; show(null); if (p) select(p); } return 'ok'; },
    hide: function(){ show(null); return 'ok'; }
  };
  return 'ok';
})();
""".trimIndent()

    fun call(fn: String, vararg args: String): String {
        val encoded = args.joinToString(",") { quote(it) }
        return "(function(){ try { return window.__vm ? window.__vm.$fn($encoded) : 'not-installed'; } catch (e) { return 'error: ' + e; } })();"
    }

    fun callRaw(fn: String, raw: String): String =
        "(function(){ try { return window.__vm ? window.__vm.$fn($raw) : 'not-installed'; } catch (e) { return 'error: ' + e; } })();"

    /** Quotes a Kotlin string as a JavaScript string literal. */
    fun quote(s: String): String {
        val sb = StringBuilder("\"")
        for (c in s) {
            when (c) {
                '\\' -> sb.append("\\\\")
                '"' -> sb.append("\\\"")
                '\n' -> sb.append("\\n")
                '\r' -> sb.append("\\r")
                '\t' -> sb.append("\\t")
                ' ' -> sb.append("\\u2028")
                ' ' -> sb.append("\\u2029")
                '<' -> sb.append("\\u003c")
                else -> sb.append(c)
            }
        }
        return sb.append('"').toString()
    }

    const val SOURCE = "(function(){ return '<!DOCTYPE html>\\n' + document.documentElement.outerHTML; })();"

    const val STORAGE = """(function(){ var o = {}; try { for (var i = 0; i < localStorage.length; i++) { var k = localStorage.key(i); o[k] = localStorage.getItem(k).slice(0, 300); } } catch (e) {} return JSON.stringify(o); })();"""

    const val PAGE_INFO = """(function(){ return JSON.stringify({ title: document.title, charset: document.characterSet, mode: document.compatMode, scripts: document.scripts.length, images: document.images.length, links: document.links.length, forms: document.forms.length, nodes: document.getElementsByTagName('*').length, viewport: innerWidth + '×' + innerHeight, ua: navigator.userAgent }); })();"""
}
