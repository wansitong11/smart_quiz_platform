"""看一份 PDF 的结构与"解压后体量"：用来解释为什么小文件也能吃爆内存。

用法： python scripts/inspect-pdf.py <file.pdf>
特点：解压流时有硬上限（默认 200MB/流），避免分析本身把机器搞爆。
"""

import re
import sys
import time
import zlib
from multiprocessing import Process, Queue

from pypdf import PdfReader

CAP = 200 * 1024 * 1024


def inflate(raw: bytes) -> int:
    """返回解压后字节数；超过 CAP 就停在 CAP（不继续吃内存）"""
    d = zlib.decompressobj()
    total = 0
    buf = raw
    while buf and total < CAP:
        piece = d.decompress(buf, CAP - total)
        total += len(piece)
        buf = d.unconsumed_tail
        if not buf:
            break
        if len(piece) == 0 and not d.unconsumed_tail:
            break
    return total


def main(path: str) -> None:
    with open(path, "rb") as f:
        data = f.read()
    print(f"文件大小: {len(data) / 1024:.1f} KB")

    reader = PdfReader(path, strict=False)
    print(f"页数: {len(reader.pages)}  加密: {reader.is_encrypted}")

    # 直接按 stream...endstream 解析原始文件，避免依赖 pypdf 内部结构
    streams = []
    for idx, m in enumerate(re.finditer(rb"stream\r?\n", data)):
        start = m.end()
        end = data.find(b"endstream", start)
        if end < 0:
            continue
        raw = data[start:end].rstrip(b"\r\n")
        dict_start = data.rfind(b"<<", max(0, m.start() - 2000), m.start())
        sdict = data[dict_start:m.start()] if dict_start >= 0 else b""
        flate = b"/FlateDecode" in sdict
        out = len(raw)
        if flate:
            try:
                out = inflate(raw)
            except Exception as exc:  # noqa: BLE001
                print(f"  流 #{idx} 解压失败: {exc}")
                out = -1
        streams.append((out, len(raw), "FlateDecode" if flate else "raw", idx))

    streams.sort(reverse=True)
    total_raw = sum(s[1] for s in streams)
    total_out = sum(s[0] for s in streams if s[0] > 0)
    print(f"流数量: {len(streams)}  原始合计: {total_raw / 1024:.1f} KB  解压合计: {total_out / 1024 / 1024:.1f} MB")
    print("最大的 8 个流（解压后 / 原始 / 编码 / 序号）:")
    for out, raw, kind, idx in streams[:8]:
        print(f"  {out / 1024 / 1024:8.2f} MB  {raw / 1024:8.1f} KB  {kind:14} #{idx}")

    # 带超时的文本提取：超时/耗时异常本身就是结论
    print("--- 文本提取测试（子进程，30s 超时）---")
    q: Queue = Queue()
    p = Process(target=_extract_worker, args=(path, q), daemon=True)
    p.start()
    p.join(30)
    if p.is_alive():
        p.terminate()
        print("提取 30 秒仍未返回 —— 这份 PDF 会让文本提取卡住（本地复现成功）")
    else:
        try:
            elapsed, chars, err = q.get_nowait()
            if err:
                print(f"提取失败({elapsed:.1f}s): {err}")
            else:
                print(f"提取完成: 用时 {elapsed:.1f}s，文本 {chars} 字符")
        except Exception:  # noqa: BLE001
            print("提取子进程异常退出")


def _extract_worker(path: str, q: Queue) -> None:
    start = time.time()
    try:
        reader = PdfReader(path, strict=False)
        text = ""
        for page in reader.pages:
            text += page.extract_text() or ""
        q.put((time.time() - start, len(text), None))
    except Exception as exc:  # noqa: BLE001
        q.put((time.time() - start, 0, repr(exc)))


if __name__ == "__main__":
    main(sys.argv[1] if len(sys.argv) > 1 else "docs/test-papers/数学知识点.pdf")
