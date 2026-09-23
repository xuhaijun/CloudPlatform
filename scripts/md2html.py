#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""Markdown → 深色主题单文件 HTML（技术文档版式）。

用途
----
把 `docs/*.md` 这类技术文档转成**可离线双击打开**的深色主题 HTML：
左侧固定 TOC 侧边栏（滚动自动高亮当前章节）、右侧正文、代码语法高亮、
代码块一键复制、回到顶部。产物是**单个 .html**，不依赖任何 CDN / 网络。

设计取舍
--------
* 深色主题、侧边栏布局是**写死**的，不提供主题切换——本项目文档一律深色，
  少一个开关就少一处配置漂移。
* 代码高亮用 pygments 的 `github-dark`（背景 #0d1117，与页面底色一致，
  不会出现"代码块比页面更亮"的突兀感）。
* 不做 HTML 净化：输入是本仓库自己的 md，可信。
* 源文件里的 `## 目录` 章节会被**自动移除**——目录由侧边栏承担，避免重复。

依赖
----
    pip install markdown pygments

用法
----
    python scripts/md2html.py docs/服务器与云端部署指南.md
    python scripts/md2html.py docs/a.md docs/b.md      # 批量
    python scripts/md2html.py docs/a.md -o /tmp/a.html # 指定输出
    python scripts/md2html.py docs/a.md --open         # 生成后用浏览器打开

约定
----
* 页面标题取 md 里第一个 `# ` 标题；`>` 引用块若紧跟在标题后，会被识别为
  「定位说明」渲染成页头摘要卡（本项目文档的统一写法）。
* 侧边栏收录 `##`（章）与 `###`（节）两级标题。
"""
from __future__ import annotations

import argparse
import io
import os
import re
import sys
import webbrowser

try:
    import markdown
    from pygments.formatters import HtmlFormatter
except ImportError:  # 给出可执行的修复命令，而不是一句 traceback
    sys.stderr.write(
        "缺少依赖。请先安装：\n"
        '  "C:/Users/xuhai/.workbuddy/binaries/python/envs/default/Scripts/pip.exe" '
        "install markdown pygments\n"
    )
    raise SystemExit(2)


# ============================================================
# 一、锚点 slug：与 GitHub / 本文档手写目录保持同一套规则
# ------------------------------------------------------------
# 规则：转小写 → 去掉标点（含中文全角括号、顿号、斜杠、点号）→ 空格换连字符。
# 例：`## 5. 包上传（代码/产物怎么上服务器）` → `5-包上传代码产物怎么上服务器`
# 这样 md 里手写的 `[x](#5-包上传代码产物怎么上服务器)` 在 HTML 里依然有效。
# ============================================================
_PUNCT = re.compile(r"[^\w\s-]+", re.UNICODE)  # \w 在 Python3 下已含中文


def slugify(value: str, separator: str = "-") -> str:
    value = value.strip().lower()
    value = _PUNCT.sub("", value)
    value = re.sub(r"\s+", separator, value)
    return value


# ============================================================
# 二、页面样式（深色主题）
# ============================================================
CSS = r"""
:root{
  --bg:#0d1117;
  --bg-side:#090c11;
  --bg-elev:#161b22;
  --bg-code:#161b22;
  --border:#21262d;
  --border-soft:#1a1f27;
  --text:#c9d1d9;
  --text-strong:#e6edf3;
  --text-dim:#8b949e;
  --accent:#58a6ff;
  --accent-soft:rgba(88,166,255,.12);
  --green:#3fb950;
  --orange:#d29922;
  --red:#f85149;
  --purple:#bc8cff;
  --sidebar-w:308px;
  --radius:8px;
}
*{box-sizing:border-box}
html{scroll-behavior:smooth}
body{
  margin:0;background:var(--bg);color:var(--text);
  font-family:-apple-system,BlinkMacSystemFont,"Segoe UI","PingFang SC",
              "Hiragino Sans GB","Microsoft YaHei",sans-serif;
  font-size:15.5px;line-height:1.78;
  -webkit-font-smoothing:antialiased;
}
code,pre,kbd,.mono{
  font-family:"JetBrains Mono","Cascadia Code",Consolas,"Courier New",monospace;
  font-variant-ligatures:none;
}

/* ---------- 布局：左固定 TOC / 右正文 ---------- */
.layout{display:flex;align-items:flex-start;min-height:100vh}

.sidebar{
  width:var(--sidebar-w);flex:0 0 var(--sidebar-w);
  position:sticky;top:0;height:100vh;overflow-y:auto;
  background:var(--bg-side);border-right:1px solid var(--border);
  padding:22px 0 60px;
}
.sidebar-brand{
  padding:0 20px 16px;margin-bottom:14px;border-bottom:1px solid var(--border-soft);
}
.sidebar-brand .t{font-size:14px;font-weight:600;color:var(--text-strong);letter-spacing:.2px}
.sidebar-brand .s{font-size:12px;color:var(--text-dim);margin-top:4px}

.toc{padding:0 10px}
.toc ul{list-style:none;margin:0;padding:0}
.toc > ul > li{margin:2px 0}
.toc a{
  display:block;padding:6px 10px;border-radius:6px;
  color:var(--text-dim);text-decoration:none;font-size:13.5px;
  border-left:2px solid transparent;transition:all .15s ease;
  overflow:hidden;text-overflow:ellipsis;white-space:nowrap;
}
.toc > ul > li > a{color:var(--text);font-weight:500}
.toc a:hover{background:var(--bg-elev);color:var(--text-strong)}
.toc a.active{
  color:var(--accent);background:var(--accent-soft);
  border-left-color:var(--accent);font-weight:600;
}
.toc ul ul{margin-left:12px}
.toc ul ul a{font-size:12.8px;padding:4px 10px}

/* ---------- 正文 ---------- */
.content{
  flex:1 1 auto;min-width:0;max-width:1000px;
  padding:44px 56px 120px;
}
.content > *:first-child{margin-top:0}

/* 页头 */
.doc-header{margin-bottom:34px;padding-bottom:22px;border-bottom:1px solid var(--border)}
h1{
  font-size:30px;line-height:1.35;margin:0 0 16px;color:var(--text-strong);
  font-weight:700;letter-spacing:-.3px;
}
.doc-meta{font-size:12.5px;color:var(--text-dim);display:flex;gap:14px;flex-wrap:wrap}
.doc-meta span{display:inline-flex;align-items:center;gap:5px}
.doc-meta code{font-size:12px;color:var(--text-dim);background:transparent;padding:0}

/* 「定位说明」摘要卡（md 中紧跟 h1 的引用块） */
.doc-lead{
  background:linear-gradient(180deg,rgba(88,166,255,.07),rgba(88,166,255,.02));
  border:1px solid rgba(88,166,255,.22);border-left:3px solid var(--accent);
  border-radius:var(--radius);padding:14px 18px;margin:0 0 30px;
  font-size:14px;color:var(--text-dim);line-height:1.8;
}
.doc-lead p{margin:0 0 8px}
.doc-lead p:last-child{margin-bottom:0}
.doc-lead strong{color:var(--text-strong)}
.doc-lead code{font-size:12.5px;color:var(--accent);background:rgba(88,166,255,.1);padding:1px 5px;border-radius:4px}

/* 标题层级 */
h2{
  font-size:23px;font-weight:650;color:var(--text-strong);
  margin:56px 0 18px;padding:0 0 10px 0;
  border-bottom:1px solid var(--border);position:relative;
}
h2::before{
  content:"";position:absolute;left:-56px;top:6px;width:3px;height:20px;
  background:var(--accent);border-radius:2px;
}
h3{
  font-size:18px;font-weight:600;color:var(--accent);
  margin:34px 0 12px;padding-left:0;
}
h4{font-size:15.5px;font-weight:600;color:var(--text-strong);margin:24px 0 10px}
h5,h6{font-size:14.5px;font-weight:600;color:var(--text-dim);margin:20px 0 8px}

/* 标题锚点链接（hover 显现） */
.headerlink{
  color:var(--text-dim);text-decoration:none;opacity:0;
  margin-left:8px;font-size:.75em;transition:opacity .15s ease;
}
h2:hover .headerlink,h3:hover .headerlink,h4:hover .headerlink{opacity:.7}
.headerlink:hover{opacity:1;color:var(--accent)}

p{margin:0 0 15px}
a{color:var(--accent);text-decoration:none}
a:hover{text-decoration:underline;text-underline-offset:3px}

strong{color:var(--text-strong);font-weight:650}
em{color:var(--text-strong)}
hr{border:0;border-top:1px solid var(--border);margin:38px 0}

ul,ol{margin:0 0 15px;padding-left:26px}
li{margin:5px 0}
li > ul,li > ol{margin:5px 0}
ul li::marker{color:var(--text-dim)}
ol li::marker{color:var(--accent);font-weight:600}

/* 引用块（除 .doc-lead 外的） */
blockquote{
  margin:0 0 16px;padding:12px 18px;
  background:var(--bg-elev);border-left:3px solid var(--border);
  border-radius:0 var(--radius) var(--radius) 0;color:var(--text-dim);
}
blockquote p:last-child{margin-bottom:0}

/* 行内代码 */
code{
  font-size:13px;background:rgba(110,118,129,.18);color:#79c0ff;
  padding:2px 6px;border-radius:5px;white-space:nowrap;
}
pre code{background:none;padding:0;color:inherit;white-space:pre;font-size:13.2px}
pre{
  margin:0;padding:16px 18px;overflow-x:auto;
  background:var(--bg-code);border:1px solid var(--border);border-radius:var(--radius);
  line-height:1.62;
}

/* 代码块容器 + 复制按钮 */
.highlight{
  position:relative;margin:0 0 18px;
  background:var(--bg-code);border:1px solid var(--border);border-radius:var(--radius);
  overflow:hidden;
}
.highlight pre{border:0;border-radius:0;background:transparent}
.copy-btn{
  position:absolute;top:8px;right:8px;z-index:2;
  background:rgba(110,118,129,.22);color:var(--text-dim);
  border:1px solid var(--border);border-radius:6px;
  padding:3px 9px;font-size:11.5px;cursor:pointer;
  font-family:inherit;opacity:0;transition:opacity .15s ease,background .15s ease;
}
.highlight:hover .copy-btn{opacity:1}
.copy-btn:hover{background:rgba(88,166,255,.22);color:var(--accent);border-color:rgba(88,166,255,.4)}
.copy-btn.done{color:var(--green);border-color:rgba(63,185,80,.45)}

/* 任务列表（- [ ] / - [x]）*/
li.task{list-style:none;margin-left:-22px;display:flex;align-items:flex-start;gap:9px}
li.task > p{display:contents}
li.task .cb{
  flex:0 0 auto;width:14px;height:14px;margin-top:6px;
  border:1.5px solid var(--text-dim);border-radius:3px;
}
li.task.done .cb{background:var(--green);border-color:var(--green)}
li.task.done{color:var(--text-dim)}

/* 表格 */
.table-wrap{overflow-x:auto;margin:0 0 18px}
table{
  border-collapse:collapse;width:100%;font-size:14px;
  border:1px solid var(--border);border-radius:var(--radius);overflow:hidden;
}
thead th{
  background:var(--bg-elev);color:var(--text-strong);font-weight:600;
  text-align:left;padding:10px 14px;border-bottom:1px solid var(--border);
  white-space:nowrap;
}
tbody td{padding:9px 14px;border-bottom:1px solid var(--border-soft);vertical-align:top}
tbody tr:last-child td{border-bottom:0}
tbody tr:nth-child(even){background:rgba(255,255,255,.014)}
tbody tr:hover{background:rgba(88,166,255,.05)}
td code,th code{font-size:12.5px}

/* 回到顶部 */
#to-top{
  position:fixed;right:26px;bottom:26px;z-index:10;
  width:40px;height:40px;border-radius:50%;
  background:var(--bg-elev);color:var(--text-dim);
  border:1px solid var(--border);cursor:pointer;font-size:16px;
  display:flex;align-items:center;justify-content:center;
  opacity:0;pointer-events:none;transition:all .2s ease;
}
#to-top.show{opacity:1;pointer-events:auto}
#to-top:hover{color:var(--accent);border-color:var(--accent);transform:translateY(-2px)}

/* 窄屏：侧边栏收起 */
#menu-btn{
  display:none;position:fixed;top:14px;left:14px;z-index:20;
  width:38px;height:38px;border-radius:8px;
  background:var(--bg-elev);border:1px solid var(--border);
  color:var(--text);cursor:pointer;font-size:17px;
}
@media (max-width:960px){
  #menu-btn{display:block}
  .sidebar{
    position:fixed;left:0;top:0;z-index:15;height:100vh;
    transform:translateX(-100%);transition:transform .22s ease;
    box-shadow:0 0 40px rgba(0,0,0,.5);
  }
  .sidebar.open{transform:translateX(0)}
  .content{padding:70px 22px 100px}
  h2::before{display:none}
}
@media print{
  .sidebar,#to-top,#menu-btn,.copy-btn{display:none}
  body{background:#fff;color:#111}
  .content{max-width:none;padding:0}
}
"""


# ============================================================
# 三、页面脚本（滚动高亮 / 复制代码 / 回到顶部）
# ============================================================
JS = r"""
(function () {
  'use strict';

  /* --- 1. 侧边栏滚动高亮当前章节 --- */
  var links = Array.prototype.slice.call(document.querySelectorAll('.toc a'));
  var targets = links
    .map(function (a) {
      var id = decodeURIComponent(a.getAttribute('href').slice(1));
      var el = document.getElementById(id);
      return el ? { link: a, el: el } : null;
    })
    .filter(Boolean);

  function setActive(link) {
    links.forEach(function (a) { a.classList.remove('active'); });
    if (!link) return;
    link.classList.add('active');
    // 让高亮项始终留在侧边栏可视区内
    var bar = document.querySelector('.sidebar');
    if (!bar) return;
    var r = link.getBoundingClientRect(), b = bar.getBoundingClientRect();
    if (r.top < b.top + 60 || r.bottom > b.bottom - 40) {
      bar.scrollTop += r.top - b.top - bar.clientHeight / 3;
    }
  }

  var ticking = false;
  function onScroll() {
    if (ticking) return;
    ticking = true;
    requestAnimationFrame(function () {
      ticking = false;
      var best = null;
      for (var i = 0; i < targets.length; i++) {
        if (targets[i].el.getBoundingClientRect().top <= 120) best = targets[i];
        else break;
      }
      if (best) setActive(best.link);
    });
  }
  window.addEventListener('scroll', onScroll, { passive: true });
  onScroll();

  /* --- 2. 代码块复制按钮 --- */
  document.querySelectorAll('.highlight').forEach(function (block) {
    var code = block.querySelector('code');
    if (!code) return;
    var btn = document.createElement('button');
    btn.className = 'copy-btn';
    btn.type = 'button';
    btn.textContent = '复制';
    btn.addEventListener('click', function () {
      var text = code.innerText;
      var done = function () {
        btn.textContent = '已复制';
        btn.classList.add('done');
        setTimeout(function () {
          btn.textContent = '复制';
          btn.classList.remove('done');
        }, 1400);
      };
      if (navigator.clipboard && window.isSecureContext) {
        navigator.clipboard.writeText(text).then(done, function () { fallback(text, done); });
      } else {
        fallback(text, done);
      }
    });
    block.appendChild(btn);
  });

  // file:// 下 navigator.clipboard 不可用时的兜底
  function fallback(text, done) {
    var ta = document.createElement('textarea');
    ta.value = text;
    ta.style.position = 'fixed';
    ta.style.opacity = '0';
    document.body.appendChild(ta);
    ta.select();
    try { document.execCommand('copy'); done(); } catch (e) { /* 忽略 */ }
    document.body.removeChild(ta);
  }

  /* --- 3. 回到顶部 --- */
  var top = document.getElementById('to-top');
  if (top) {
    top.addEventListener('click', function () {
      window.scrollTo({ top: 0, behavior: 'smooth' });
    });
    window.addEventListener('scroll', function () {
      top.classList.toggle('show', window.scrollY > 500);
    }, { passive: true });
  }

  /* --- 4. 窄屏侧边栏 --- */
  var menuBtn = document.getElementById('menu-btn');
  var sidebar = document.querySelector('.sidebar');
  if (menuBtn && sidebar) {
    menuBtn.addEventListener('click', function () { sidebar.classList.toggle('open'); });
    sidebar.addEventListener('click', function (e) {
      if (e.target.tagName === 'A') sidebar.classList.remove('open');
    });
  }
})();
"""


# ============================================================
# 四、Markdown 解析
# ============================================================
HEADING_RE = re.compile(r"^(#{1,6})\s+(.*?)\s*#*$")

# ---------- 块级预处理（列表 & 表格） ----------
# GitHub / CommonMark 允许列表、表格直接贴在段落后面；Python-Markdown **不允许**
# （两者都要求前面有空行），否则整块退化成一段纯文本 —— `-` 或 `|` 会原样显示。
# 本项目文档大量使用「引导语 + 紧跟列表/表格」的写法（实测 24 处列表 + 4 处表格），
# 因此解析前做一次无损预处理：只在需要处补空行，不动任何文字。
_FENCE_RE = re.compile(r"^\s*(```|~~~)")
_LIST_RE = re.compile(r"^\s*(?:>\s*)*[-*+]\s|^\s*(?:>\s*)*\d+[.)]\s")
_TABLE_RE = re.compile(r"^\s*(?:>\s*)*\|")
_QUOTE_RE = re.compile(r"^\s*(?:>\s*)+")


def _is_list_item(line: str) -> bool:
    return bool(_LIST_RE.match(line))


def _is_loose_block_start(line: str) -> bool:
    """列表项或表格行 —— 这两类块在 Python-Markdown 里都要求前面有空行。"""
    return bool(_LIST_RE.match(line) or _TABLE_RE.match(line))


def _quote_prefix(line: str) -> str:
    """引用前缀（如 `> ` / `> > `）；非引用行返回 ''。"""
    m = _QUOTE_RE.match(line)
    return m.group(0) if m else ""


def _strip_quote(line: str) -> str:
    return _QUOTE_RE.sub("", line)


def _needs_blank_before(out: list[str], line: str) -> bool:
    """
    判断在 line 之前是否需要补一个空行（即它开启了一个新列表块）。

    向上回溯：跳过「缩进续行」，直到遇到一个「块起始行」。
      * 空行               → 已有分隔，不用补
      * 列表项 / 表格行    → 同一块的后续行，不用补
      * 其他（段落/标题/分隔线） → 是新块，要补
    引用块内按去掉 `>` 后的内容判断，且不跨出引用边界。
    """
    quote = _quote_prefix(line)

    def content_of(raw: str):
        if quote:
            return _strip_quote(raw) if _strip_quote(raw) != raw else None
        return None if raw.lstrip().startswith(">") else raw

    for raw in reversed(out[-20:]):
        c = content_of(raw)
        if c is None:
            return False          # 引用边界不匹配（引用块已在上一行结束）
        if not c.strip():
            return False          # 已有空行
        if _is_loose_block_start(c):
            return False          # 同一列表 / 同一表格
        if c[:1] in (" ", "\t"):
            continue              # 缩进续行 → 继续回溯
        return True
    return False


def normalize_blocks(src: str) -> str:
    """给「紧贴段落的列表 / 表格」补空行（围栏代码块内原样跳过）。"""
    lines = src.split("\n")
    out: list[str] = []
    in_fence = False
    fence_token = ""

    for line in lines:
        fm = _FENCE_RE.match(line)
        if fm:
            if not in_fence:
                in_fence, fence_token = True, fm.group(1)
            elif line.strip().startswith(fence_token):
                in_fence = False
            out.append(line)
            continue
        if in_fence:
            out.append(line)
            continue

        if _is_loose_block_start(line) and out and _needs_blank_before(out, line):
            qp = _quote_prefix(line)
            # 引用块内补「空引用行」，否则会把后续内容踢出引用块
            out.append(qp.rstrip() if qp else "")

        out.append(line)

    return "\n".join(out)


def extract_title(src: str) -> tuple[str, str]:
    """返回 (标题, 去除首个 h1 后的正文)。"""
    lines = src.splitlines()
    for i, line in enumerate(lines):
        m = HEADING_RE.match(line)
        if m and len(m.group(1)) == 1:
            title = m.group(2).strip()
            rest = "\n".join(lines[:i] + lines[i + 1:])
            return title, rest
    return "未命名文档", src


def drop_manual_toc(src: str) -> str:
    """删掉源文件里的 `## 目录` 章节——侧边栏已承担，避免重复。"""
    pattern = re.compile(r"^##\s*目录\s*$.*?(?=^##\s)", re.MULTILINE | re.DOTALL)
    return pattern.sub("", src, count=1)


def split_lead(src: str) -> tuple[str, str]:
    """把紧跟 h1 的引用块抽出来做页头摘要卡（本项目文档的统一写法）。"""
    lines = src.split("\n")
    i = 0
    while i < len(lines) and not lines[i].strip():
        i += 1
    if i >= len(lines) or not lines[i].lstrip().startswith(">"):
        return "", src
    start = i
    while i < len(lines) and (lines[i].lstrip().startswith(">") or not lines[i].strip()):
        # 连续空行即认为引用块结束
        if not lines[i].strip() and i + 1 < len(lines) and not lines[i + 1].lstrip().startswith(">"):
            break
        i += 1
    lead = "\n".join(lines[start:i])
    rest = "\n".join(lines[:start] + lines[i:])
    return lead, rest


def build_markdown() -> markdown.Markdown:
    """
    刻意**不用 `extra` 这个合集扩展**，因为它内含 `sane_lists`。

    `sane_lists` 要求「列表前必须有空行」，而本文档（以及大多数 GitHub 上写就的
    中文技术文档）习惯把列表紧贴在引导语后面：

        **怎么选**：
        - 要备案 + 国内低延迟 → 国内大厂轻量应用服务器。

    在 GitHub / CommonMark 下这是合法的列表，但被 `sane_lists` 一拦，就退化成
    一段纯文本，`-` 会原样显示出来。实测本文档有 6 处引用块内列表 + 十余处
    紧贴段落的列表会被吃掉，所以这里逐个列出所需扩展、剔除 `sane_lists`，
    让渲染结果与 GitHub 上的观感一致。
    """
    return markdown.Markdown(
        extensions=[
            "tables",        # 表格
            "fenced_code",   # ``` 围栏代码块（codehilite 依赖它）
            "attr_list",     # {.class} 行内属性
            "def_list",      # 定义列表
            "footnotes",     # 脚注
            "abbr",          # 缩写
            "md_in_html",    # 允许在 HTML 块里写 md
            "toc",           # 标题锚点 + 目录
            "codehilite",    # 语法高亮（pygments）
        ],
        extension_configs={
            "toc": {
                "slugify": slugify,
                "toc_depth": "2-3",   # 只要章与节，不要文档标题
                # anchorlink 会生成 `<a class="toclink">整个标题文字</a>`，
                # 让标题文字变成链接色、破坏排版。这里关掉，改由
                # add_headerlinks() 在标题末尾插一个 hover 才显形的 `#`。
                "anchorlink": False,
                "permalink": False,
            },
            "codehilite": {
                "guess_lang": False,  # 不猜语言：命令输出块不该被乱上色
                "css_class": "highlight",
            },
        },
        output_format="html5",
    )


_HEADING_HTML_RE = re.compile(r'<h([2-4]) id="([^"]+)">(.*?)</h\1>')


def add_headerlinks(html: str) -> str:
    """给 h2~h4 末尾插一个锚点 `#`（hover 才显形，见 CSS .headerlink）。"""
    def repl(m: re.Match) -> str:
        lvl, hid, inner = m.group(1), m.group(2), m.group(3)
        if "headerlink" in inner:
            return m.group(0)
        return (f'<h{lvl} id="{hid}">{inner}'
                f'<a class="headerlink" href="#{hid}" title="永久链接">#</a></h{lvl}>')

    return _HEADING_HTML_RE.sub(repl, html)


_TASK_RE = re.compile(r'<li>(<p>)?\[([ xX])\]\s*')


def style_task_lists(html: str) -> str:
    """把 GitHub 风格任务列表 `- [ ]` / `- [x]` 渲染成方框。

    Python-Markdown 不带 tasklist 扩展，默认会把 `[ ]` 原样输出成文字。
    这里改写成 `<span class="cb">`，由 CSS 画方框。
    `<p>` 要一并匹配：松散列表会把内容包进段落，方框插在 `<p>` 内才对。
    """
    def repl(m: re.Match) -> str:
        para = m.group(1) or ""
        done = m.group(2).lower() == "x"
        return f'<li class="{"task done" if done else "task"}">{para}<span class="cb"></span>'

    return _TASK_RE.sub(repl, html)


def unescape_cell_pipes(html: str) -> str:
    """还原表格单元格内行内代码里的 `\\|` → `|`。

    Markdown 表格的单元格里，`|` 必须写成 `\\|` 才不会被当成分隔符；
    但 Python-Markdown 的 code span **不做转义还原**，于是
    `` `nginx -T \\| grep x` `` 会原样渲染出反斜杠，显示成 `nginx -T \\| grep x`。

    此时表格结构已解析完毕（单元格边界确定、不再有歧义），统一还原即可。
    注意：单元格内若真需要显示 `\\|`（如 awk 转义示例），会被一并还原——
    本项目文档无此场景；若有，请改用 `&#124;` 之外的写法或拆分表格。
    """
    return re.sub(
        r"<t[dh][^>]*>.*?</t[dh]>",
        lambda m: m.group(0).replace("\\|", "|"),
        html,
        flags=re.DOTALL,
    )


def wrap_tables(html: str) -> str:
    """表格包一层容器，窄屏可横向滚动（宽表格不被压缩变形）。"""
    return re.sub(
        r"(<table>.*?</table>)",
        r'<div class="table-wrap">\1</div>',
        html,
        flags=re.DOTALL,
    )


def page(title: str, lead_html: str, toc_html: str, body_html: str, src_rel: str,
         pyg_css: str) -> str:
    lead_block = f'<div class="doc-lead">\n{lead_html}\n</div>\n' if lead_html.strip() else ""
    return f"""<!DOCTYPE html>
<html lang="zh-CN">
<head>
<meta charset="utf-8">
<meta name="viewport" content="width=device-width, initial-scale=1">
<title>{title}</title>
<meta name="generator" content="scripts/md2html.py">
<style>
{CSS}
/* ---------- pygments 语法高亮（github-dark） ---------- */
{pyg_css}
.highlight .highlight {{ background: transparent; }}
</style>
</head>
<body>
<button id="menu-btn" type="button" aria-label="目录">&#9776;</button>
<div class="layout">
  <aside class="sidebar">
    <div class="sidebar-brand">
      <div class="t">{title}</div>
      <div class="s">{src_rel}</div>
    </div>
    <nav class="toc">
{toc_html}
    </nav>
  </aside>
  <main class="content">
    <header class="doc-header">
      <h1>{title}</h1>
      <div class="doc-meta">
        <span>源文件 <code>{src_rel}</code></span>
        <span>由 scripts/md2html.py 生成</span>
      </div>
    </header>
    {lead_block}{body_html}
  </main>
</div>
<button id="to-top" type="button" title="回到顶部" aria-label="回到顶部">&#8593;</button>
<script>
{JS}
</script>
</body>
</html>
"""


# ============================================================
# 五、入口
# ============================================================
def convert(src_path: str, out_path: str | None) -> str:
    src_rel = src_path.replace("\\", "/")
    with io.open(src_path, encoding="utf-8") as f:
        raw = f.read()

    title, body = extract_title(raw)
    lead_md, body = split_lead(body)
    body = drop_manual_toc(body)

    # 预处理：Python-Markdown 要求列表/表格前有空行，这里把「紧贴段落」的补上
    body = normalize_blocks(body)
    lead_md = normalize_blocks(lead_md)

    md = build_markdown()
    body_html = add_headerlinks(style_task_lists(unescape_cell_pipes(wrap_tables(md.convert(body)))))
    toc_html = md.toc or "<ul><li>（无标题）</li></ul>"

    md_lead = build_markdown()
    lead_html = md_lead.convert(lead_md) if lead_md.strip() else ""

    pyg_css = HtmlFormatter(style="github-dark").get_style_defs(".highlight")
    # 代码块背景统一交给页面主题控制，避免两种底色
    pyg_css = re.sub(r"\.highlight\s*\{[^}]*\}", ".highlight { background: transparent; }", pyg_css)
    # github-dark 没有声明这几个 token（YAML 的 plain scalar 与 indicator），
    # 缺了它们不会报错、只会静默回退成正文色，看起来像"这块没上色"。
    # 用该主题自身的调色补齐：字符串 #A5D6FF、标点 #E6EDF3。
    pyg_css += (
        "\n.highlight .l-Scalar, .highlight .l-Scalar-Plain { color:#A5D6FF }"
        "\n.highlight .p-Indicator { color:#E6EDF3 }"
    )

    html = page(title, lead_html, toc_html, body_html, src_rel, pyg_css)

    if out_path is None:
        out_path = os.path.splitext(src_path)[0] + ".html"
    with io.open(out_path, "w", encoding="utf-8", newline="\n") as f:
        f.write(html)

    size_kb = os.path.getsize(out_path) / 1024
    print(f"[OK] {src_rel}  ->  {out_path}  ({size_kb:.1f} KB)")
    return out_path


def main() -> int:
    ap = argparse.ArgumentParser(
        description="Markdown → 深色主题单文件 HTML（技术文档版式）",
    )
    ap.add_argument("inputs", nargs="+", help="输入 .md 文件")
    ap.add_argument("-o", "--output", help="输出路径（仅在单个输入时可用）")
    ap.add_argument("--open", action="store_true", help="生成后用默认浏览器打开")
    args = ap.parse_args()

    if args.output and len(args.inputs) > 1:
        ap.error("-o/--output 只能在单个输入时使用")

    produced = []
    for i, src in enumerate(args.inputs):
        if not os.path.isfile(src):
            sys.stderr.write(f"[SKIP] 文件不存在：{src}\n")
            continue
        produced.append(convert(src, args.output if len(args.inputs) == 1 else None))

    if args.open and produced:
        webbrowser.open("file:///" + os.path.abspath(produced[0]).replace("\\", "/"))
    return 0 if produced else 1


if __name__ == "__main__":
    # Windows 控制台默认 GBK，中文输出会抛 UnicodeEncodeError
    try:
        sys.stdout.reconfigure(encoding="utf-8", errors="replace")
        sys.stderr.reconfigure(encoding="utf-8", errors="replace")
    except Exception:
        pass
    raise SystemExit(main())
