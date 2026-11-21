import http.server, os, re, socketserver, json, urllib.parse, urllib.request

ROOT = "/opt/models"
CHUNK = 256 * 1024

UA = ("Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 "
      "(KHTML, like Gecko) Chrome/122.0 Safari/537.36")

# ─────────────────────────────────────────────────────────────
# 联网搜索代理
#
# 为什么放在这里而不是让 App 直连搜索服务：
#   · 大陆直连不了 Google / DuckDuckGo
#   · 搜索 API 的 key 不能打进 APK（反编译就泄露）
#   · 这台机器在境外，直连没有障碍
#
# 用的是 DuckDuckGo 的 HTML 端点（html.duckduckgo.com），
# 不需要 API key。它的页面结构相对稳定，而且给的是无 JS 的纯 HTML，
# 解析比 Bing/Google 简单得多。
# ─────────────────────────────────────────────────────────────

SEARXNG = "http://127.0.0.1:8888/search"

# 只用实测能出结果的引擎。
#
# SearXNG 默认会并发问十几个引擎，其中一半在这台机器的 IP 上是被拦的
# （返回空 + 拖到超时）。限定成这几个可用的，响应时间从 8 秒降到 1-2 秒，
# 而且避免"某个引擎卡住导致整体超时"。
USABLE_ENGINES = "google,bing,brave,wikipedia"


def do_search(query, n):
    """
    走本机的 SearXNG（127.0.0.1:8888）拿结果。

    ── 为什么不自己抓各家的搜索页 ──────────────────────────────────
    实测过，两条路都堵死：
      · Google 的搜索页现在是 JS 应用，无浏览器引擎抓回来是空壳
        （页面里有 enablejs 的自动跳转）
      · DuckDuckGo 会给挑战页（14KB，没有任何结果结构）

    SearXNG 维护着各引擎的适配层，引擎改版由它跟进，我们不用管。
    代价是多一个本地进程（约 200MB 内存，已设 400MB 上限）。
    """
    url = (SEARXNG + "?q=" + urllib.parse.quote(query)
           + "&format=json&engines=" + USABLE_ENGINES)
    req = urllib.request.Request(url, headers={"User-Agent": UA})
    with urllib.request.urlopen(req, timeout=15) as r:
        data = json.loads(r.read().decode("utf-8", "replace"))

    out = []
    for item in data.get("results", []):
        title = (item.get("title") or "").strip()
        link = (item.get("url") or "").strip()
        snippet = re.sub(r"\s+", " ", (item.get("content") or "")).strip()
        if not title and not snippet:
            continue
        out.append({"title": title, "url": link, "snippet": snippet})
        if len(out) >= n:
            break
    return out


class RangeHandler(http.server.SimpleHTTPRequestHandler):
    def __init__(self, *a, **kw):
        super().__init__(*a, directory=ROOT, **kw)

    def do_GET(self):
        # ── 搜索接口 ──
        if self.path.startswith("/search"):
            self.handle_search()
            return

        path = self.translate_path(self.path)
        if not os.path.isfile(path):
            return super().do_GET()
        size = os.path.getsize(path)
        rng = self.headers.get("Range")
        start, end = 0, size - 1
        partial = False
        if rng:
            m = re.match(r"bytes=(\d+)-(\d*)", rng)
            if m:
                start = int(m.group(1))
                if m.group(2):
                    end = min(int(m.group(2)), size - 1)
                partial = True
        length = end - start + 1
        self.send_response(206 if partial else 200)
        self.send_header("Content-Type", "application/octet-stream")
        self.send_header("Content-Length", str(length))
        self.send_header("Accept-Ranges", "bytes")
        if partial:
            self.send_header("Content-Range", f"bytes {start}-{end}/{size}")
        self.end_headers()
        with open(path, "rb") as f:
            f.seek(start)
            left = length
            while left > 0:
                data = f.read(min(CHUNK, left))
                if not data:
                    break
                try:
                    self.wfile.write(data)
                except (BrokenPipeError, ConnectionResetError):
                    break
                left -= len(data)

    def handle_search(self):
        """
        返回 JSON 数组：[{"title":..., "url":..., "snippet":...}, ...]

        出错时返回空的 []，而不是 5xx ——
        客户端那边「搜索失败」和「搜到 0 条」的处理是一样的（都不注入
        上下文、照常回答），返回空数组能简化客户端逻辑。
        """
        try:
            qs = urllib.parse.parse_qs(urllib.parse.urlparse(self.path).query)
            q = (qs.get("q") or [""])[0].strip()
            n = int((qs.get("n") or ["5"])[0])
            n = max(1, min(n, 10))

            data = do_search(q, n) if q else []
            body = json.dumps(data, ensure_ascii=False).encode("utf-8")
            self.send_response(200)
        except Exception as e:
            body = json.dumps(
                {"error": str(e)[:200]}, ensure_ascii=False
            ).encode("utf-8") if False else b"[]"
            self.send_response(200)

        self.send_header("Content-Type", "application/json; charset=utf-8")
        self.send_header("Content-Length", str(len(body)))
        self.end_headers()
        try:
            self.wfile.write(body)
        except (BrokenPipeError, ConnectionResetError):
            pass

    def log_message(self, fmt, *args):
        pass   # 别刷日志


class Threaded(socketserver.ThreadingMixIn, http.server.HTTPServer):
    daemon_threads = True


if __name__ == "__main__":
    with Threaded(("0.0.0.0", 8899), RangeHandler) as httpd:
        print("serving /opt/models on 0.0.0.0:8899 (Range + /search)", flush=True)
        httpd.serve_forever()
