# -*- coding: utf-8 -*-
"""
石河子大学教务系统课表爬虫
================================
功能：登录学校统一身份认证(CAS) -> 短信二次认证 -> 抓取强智教务系统课表 -> 导出 JSON

抓取字段：课程名、教师、上课地点、星期、节次、时间、周次等

依赖安装：
    pip install curl_cffi pycryptodome beautifulsoup4 lxml

用法：
    python jwgl_spider.py                                  # 抓当前学期，导出到 课表.json
    python jwgl_spider.py --semester 2025-2026-1           # 指定学期
    python jwgl_spider.py --out my_schedule.json           # 指定输出文件
    python jwgl_spider.py --username 学号 --password 密码   # 命令行传账号（否则交互输入）

说明：
    - 登录流程：CAS 账号密码 -> 触发短信二次认证(MFA) -> 控制台输入短信验证码
    - jwgl.shzu.edu.cn 服务器 TLS 较老，Python 标准 requests/OpenSSL 握手会失败，
      因此使用 curl_cffi 模拟 Chrome TLS 指纹。
"""
import argparse
import base64
import json
import random
import re
import sys
from datetime import datetime

from bs4 import BeautifulSoup
from curl_cffi import requests as cr
from Crypto.Cipher import AES
from Crypto.Util.Padding import pad

CAS_LOGIN = "https://authserver.shzu.edu.cn/authserver/login"
CAS_BASE = "https://authserver.shzu.edu.cn/authserver"
SERVICE = "https://jwgl.shzu.edu.cn/sso.jsp"
JW_BASE = "https://jwgl.shzu.edu.cn"
KBCX_URL = f"{JW_BASE}/jsxsd/xskb/xskb_list.do"

# 学校前端 encrypt.js 的随机字符集
AES_CHARS = "ABCDEFGHJKMNPQRSTWXYZabcdefhijkmnprstwxyz2345678"

WEEKDAY_NAMES = ["星期一", "星期二", "星期三", "星期四", "星期五", "星期六", "星期日"]


# ---------------------------------------------------------------- 工具函数
def random_string(n: int) -> str:
    return "".join(random.choices(AES_CHARS, k=n))


def encrypt_password(password: str, salt: str) -> str:
    """复现统一身份认证前端密码加密：random(64)+pwd -> AES-CBC(Base64)"""
    if not salt:
        return password
    plaintext = (random_string(64) + password).encode("utf-8")
    cipher = AES.new(salt.encode("utf-8"), AES.MODE_CBC,
                     random_string(16).encode("utf-8"))
    return base64.b64encode(cipher.encrypt(pad(plaintext, 16))).decode()


def make_session() -> cr.Session:
    """创建模拟 Chrome TLS 指纹的会话（绕开老服务器 TLS 兼容问题）"""
    s = cr.Session(impersonate="chrome", verify=False, timeout=30)
    return s


# ---------------------------------------------------------------- 登录流程
def check_need_captcha(s: cr.Session, username: str) -> bool:
    r = s.get(f"{CAS_BASE}/checkNeedCaptcha.htl", params={"username": username})
    try:
        return "true" in r.text.lower()
    except Exception:
        return False


def save_captcha_image(s: cr.Session, path: str = "captcha.jpg") -> str:
    r = s.get(f"{CAS_BASE}/getCaptcha.htl")
    with open(path, "wb") as f:
        f.write(r.content)
    return path


def cas_login(s: cr.Session, username: str, password: str) -> bool:
    """CAS 账号密码登录；返回是否进入二次认证页"""
    r = s.get(CAS_LOGIN, params={"service": SERVICE})
    html = r.text

    salt_m = re.search(r'id="pwdEncryptSalt"\s+value="([^"]*)"', html)
    exec_m = re.search(r'name="execution"\s+value="([^"]*)"', html)
    salt = salt_m.group(1) if salt_m else ""
    execution = exec_m.group(1) if exec_m else "e1s1"

    captcha = ""
    if check_need_captcha(s, username):
        img = save_captcha_image(s)
        print(f"[!] 本次登录需要图形验证码，已保存到 {img}，请打开查看")
        captcha = input("请输入图形验证码: ").strip()

    data = {
        "username": username,
        "password": encrypt_password(password, salt),
        "_eventId": "submit",
        "cllt": "userNameLogin",
        "dllt": "generalLogin",
        "lt": "",
        "execution": execution,
    }
    if captcha:
        data["captcha"] = captcha

    r = s.post(CAS_LOGIN, params={"service": SERVICE}, data=data,
               allow_redirects=True)
    # 停在 authserver 且无 ticket = 密码错/需要处理
    if "reAuthLoginView" in str(r.url):
        print("[√] 账号密码验证通过，需要短信二次认证")
        return True
    if "ticket=" in str(r.url):
        print("[√] 账号密码验证通过（无需二次认证）")
        return False
    if "authserver" in str(r.url):
        err = re.search(r'id="showErrorTip"[^>]*>\s*([^<]*)', r.text)
        msg = err.group(1).strip() if err else "未知错误"
        raise SystemExit(f"[x] 登录失败：{msg}")
    return False


def mfa_sms_login(s: cr.Session, username: str) -> bool:
    """短信二次认证：发送验证码 -> 用户输入 -> 提交"""
    # 发送短信验证码
    r = s.post(f"{CAS_BASE}/dynamicCode/getDynamicCodeByReauth.do",
               data={"userName": username,
                     "authCodeTypeName": "reAuthDynamicCodeType"})
    try:
        info = r.json()
    except Exception:
        raise SystemExit(f"[x] 发送短信验证码失败: {r.text[:100]}")
    if str(info.get("res")) not in ("success", "other_success", "code_time_fail"):
        raise SystemExit(f"[x] 发送短信验证码失败: {info.get('returnMessage')}")
    print(f"[√] 短信验证码已发送至 {info.get('mobile', '注册手机')}")

    code = input("请输入收到的短信验证码: ").strip()

    r = s.post(f"{CAS_BASE}/reAuthCheck/reAuthSubmit.do", data={
        "service": SERVICE, "reAuthType": "3", "isMultifactor": "true",
        "password": "", "dynamicCode": code, "uuid": "",
        "answer1": "", "answer2": "", "otpCode": "",
    })
    try:
        res = r.json()
    except Exception:
        raise SystemExit(f"[x] 二次认证响应异常: {r.text[:100]}")
    if res.get("code") != "reAuth_success":
        print(f"[x] 二次认证失败: {res.get('msg')}")
        return False
    print("[√] 二次认证成功")
    return True


def enter_jwgl(s: cr.Session) -> None:
    """带着 CAS 票据进入教务系统，建立教务会话"""
    r = s.get(CAS_LOGIN, params={"service": SERVICE}, allow_redirects=True)
    url = str(r.url)
    if "xsMain" not in url and "jsxsd" not in url and "sso.jsp" not in url:
        raise SystemExit(f"[x] 进入教务系统失败，最终 URL: {url}")
    # 触发一次主页访问确保 session cookie 齐全
    s.get(f"{JW_BASE}/jsxsd/framework/xsMain.htmlx")
    print("[√] 已进入教务系统")


# ---------------------------------------------------------------- 课表抓取
def fetch_semesters(s: cr.Session) -> list:
    """从课表页面的下拉框解析可选学期"""
    r = s.get(KBCX_URL)
    soup = BeautifulSoup(r.text, "lxml")
    sel = soup.find("select", id="xnxq01id")
    if not sel:
        return []
    return [o.get("value") for o in sel.find_all("option") if o.get("value")]


def fetch_schedule_html(s: cr.Session, semester: str = "") -> str:
    params = {"xnxq01id": semester} if semester else {}
    r = s.get(KBCX_URL, params=params)
    if "学期理论课表" not in r.text:
        raise SystemExit(f"[x] 课表页面获取失败（可能学期参数错误: {semester}）")
    return r.text


def parse_schedule(html: str) -> list:
    """解析强智课表 HTML，返回课程列表（扁平结构）"""
    soup = BeautifulSoup(html, "lxml")
    table = soup.find("table", id="timetable")
    if table is None:
        raise SystemExit("[x] 未找到课表表格（页面结构可能变化）")

    courses = []
    for tr in table.find_all("tr"):
        th = tr.find("th")
        if th is None:
            continue
        # 行首：节次段信息，如 "第一二节 (01,02小节) 10:00-11:40"
        head = th.get_text(" ", strip=True)
        section_name = re.sub(r"\s+", "", head.split("(")[0]) if head else ""
        m_sec = re.search(r"\((\d+(?:,\d+)*)小节\)", head)
        m_time = re.search(r"(\d{1,2}:\d{2}-\d{1,2}:\d{2})", head)
        sections = m_sec.group(1) if m_sec else ""
        time_range = m_time.group(1) if m_time else ""

        # 后续 7 个 td 依次是星期一到星期日
        tds = tr.find_all("td")
        for day_idx, td in enumerate(tds[:7], start=1):
            # 取含教师信息的详版 div（class=kbcontent）；kbcontent1 是简版
            divs = td.find_all("div", class_="kbcontent")
            for div in divs:
                inner = div.decode_contents()
                # 同一格多门课用长横线分隔
                blocks = re.split(r"-{5,}<br\s*/?>", inner)
                for block in blocks:
                    bs = BeautifulSoup(f"<div>{block}</div>", "lxml")
                    fonts = bs.find_all("font")

                    def font_by_title(title):
                        f = bs.find("font", attrs={"title": title})
                        return f.get_text(strip=True) if f else ""

                    def font_by_name(name):
                        f = bs.find("font", attrs={"name": name})
                        return f.get_text(strip=True) if f else ""

                    # 课程名：第一个无 title 的 font
                    name = ""
                    for f in fonts:
                        if not f.get("title") and f.get_text(strip=True):
                            name = f.get_text(strip=True)
                            break
                    if not name:
                        continue

                    week_sec = font_by_title("周次(节次)")   # 如 1-12(周)[01-02节]
                    m_week = re.match(r"([0-9,\-单双]+\(周\))", week_sec)
                    weeks = m_week.group(1) if m_week else week_sec
                    m_js = re.search(r"\[([0-9\-]+节)\]", week_sec)

                    courses.append({
                        "课程名称": name,
                        "教师": font_by_title("教师"),
                        "地点": font_by_title("教室"),
                        "教学楼": font_by_title("教学楼").strip("【】"),
                        "星期": WEEKDAY_NAMES[day_idx - 1],
                        "节次": section_name,
                        "小节": f"{sections}小节" if sections else "",
                        "具体节次": m_js.group(1) if m_js else "",
                        "时间段": time_range,
                        "周次": weeks,
                        "班级": font_by_name("ktmcstr").replace("班级：", ""),
                        "备注": font_by_name("bzstr").replace("备注：", ""),
                    })
    return courses


def merge_dedup(courses: list) -> list:
    """同一门课在不同格子重复出现（如跨节次连排）时去重合并展示信息"""
    seen = {}
    for c in courses:
        key = (c["课程名称"], c["教师"], c["星期"], c["节次"], c["地点"], c["周次"])
        if key not in seen:
            seen[key] = c
    return list(seen.values())


# ---------------------------------------------------------------- 主流程
def main():
    parser = argparse.ArgumentParser(description="石河子大学教务系统课表爬虫")
    parser.add_argument("--username", "-u", default="", help="学号")
    parser.add_argument("--password", "-p", default="", help="统一身份认证密码")
    parser.add_argument("--semester", "-s", default="",
                        help="学年学期，如 2026-2027-1；留空为当前学期")
    parser.add_argument("--out", "-o", default="课表.json", help="输出 JSON 文件路径")
    parser.add_argument("--list-semesters", action="store_true",
                        help="仅列出可选学期")
    args = parser.parse_args()

    username = args.username or input("请输入学号: ").strip()
    password = args.password or input("请输入密码: ").strip()

    s = make_session()

    print(f"[1/4] 登录统一身份认证（学号 {username}）...")
    need_mfa = cas_login(s, username, password)

    print("[2/4] 二次认证...")
    if need_mfa:
        if not mfa_sms_login(s, username):
            sys.exit(1)
    enter_jwgl(s)

    print("[3/4] 获取课表...")
    if args.list_semesters:
        semesters = fetch_semesters(s)
        print("可选学期:", ", ".join(semesters))
        return
    html = fetch_schedule_html(s, args.semester)
    # 从页面解析当前显示的学期
    cur = BeautifulSoup(html, "lxml").find("select", id="xnxq01id")
    cur_val = ""
    if cur:
        opt = cur.find("option", attrs={"selected": "selected"})
        cur_val = opt.get("value", "") if opt else ""
    semester = args.semester or cur_val

    courses = merge_dedup(parse_schedule(html))

    print(f"[4/4] 解析完成：共 {len(courses)} 条上课记录，导出到 {args.out}")
    result = {
        "学号": username,
        "学期": semester,
        "抓取时间": datetime.now().strftime("%Y-%m-%d %H:%M:%S"),
        "课程数量": len(courses),
        "课程": courses,
    }
    with open(args.out, "w", encoding="utf-8") as f:
        json.dump(result, f, ensure_ascii=False, indent=2)

    # 控制台预览
    print("\n========== 课表预览 ==========")
    for c in courses:
        print(f"{c['星期']} {c['节次']} {c['时间段']} | {c['课程名称']} "
              f"| {c['教师']} | {c['地点']} | {c['周次']}")


if __name__ == "__main__":
    main()
