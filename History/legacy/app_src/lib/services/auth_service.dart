import 'dart:convert';
import 'dart:math';

import 'package:dio/dio.dart';
import 'package:encrypt/encrypt.dart';

import 'api_client.dart';

/// 登录结果
enum LoginResult { success, needMfa, needCaptchaImage, failed }

/// 石河子大学统一身份认证登录服务
class AuthService {
  static const casLogin = 'https://authserver.shzu.edu.cn/authserver/login';
  static const casBase = 'https://authserver.shzu.edu.cn/authserver';
  static const service = 'https://jwgl.shzu.edu.cn/sso.jsp';
  static const jwBase = 'https://jwgl.shzu.edu.cn';

  static const aesChars = 'ABCDEFGHJKMNPQRSTWXYZabcdefhijkmnprstwxyz2345678';

  final ApiClient client;

  AuthService(this.client);

  static String _randomString(int n) {
    final rng = Random.secure();
    return List.generate(n, (_) => aesChars[rng.nextInt(aesChars.length)]).join();
  }

  /// 复现前端 encryptPassword：random(64)+pwd -> AES-CBC(Base64)
  static String encryptPassword(String password, String salt) {
    if (salt.isEmpty) return password;
    final plaintext = _randomString(64) + password;
    final key = Key.fromUtf8(salt);
    final iv = IV.fromUtf8(_randomString(16));
    final encrypter = Encrypter(AES(key, mode: AESMode.cbc, padding: 'PKCS7'));
    return encrypter.encrypt(plaintext, iv: iv).base64;
  }

  static String _extract(String html, String pattern) {
    final m = RegExp(pattern).firstMatch(html);
    return m?.group(1) ?? '';
  }

  /// 是否需要图形验证码
  Future<bool> needCaptcha(String username) async {
    try {
      final r = await client.dio.get('$casBase/checkNeedCaptcha.htl',
          queryParameters: {'username': username});
      return r.data.toString().contains('true');
    } catch (_) {
      return false;
    }
  }

  /// 密码登录；返回结果与错误信息
  Future<(LoginResult, String)> loginWithPassword(
      String username, String password) async {
    final r = await client.dio.get(casLogin,
        queryParameters: {'service': service});
    // 已登录（存在 CAS TGT）时，首次 GET 会直接 302 到 sso.jsp，无需再密码登录
    if (ApiClient.isRedirect(r.statusCode)) {
      return (LoginResult.success, '');
    }
    final html = r.data.toString();
    final salt = _extract(
        html, r'id="pwdEncryptSalt"\s+value="([^"]*)"');
    final execution = _extract(
        html, r'name="execution"\s+value="([^"]*)"');

    final needImg = await needCaptcha(username);
    final data = {
      'username': username,
      'password': encryptPassword(password, salt),
      '_eventId': 'submit',
      'cllt': 'userNameLogin',
      'dllt': 'generalLogin',
      'lt': '',
      'execution': execution.isEmpty ? 'e1s1' : execution,
    };
    if (needImg) {
      return (LoginResult.needCaptchaImage, '');
    }

    final resp = await client.dio.post(casLogin,
        queryParameters: {'service': service},
        data: data,
        options: Options(contentType: Headers.formUrlEncodedContentType));

    final body = resp.data.toString();
    // followRedirects 已关闭，用 Location 头判断跳转目标
    final loc = resp.headers.value('location') ?? '';
    final url = loc.isNotEmpty
        ? Uri.parse(casLogin).resolve(loc).toString()
        : resp.realUri.toString();

    if (url.contains('reAuthLoginView')) {
      return (LoginResult.needMfa, '');
    }
    if (url.contains('ticket=')) {
      return (LoginResult.success, '');
    }
    if (url.contains('authserver')) {
      final err = _extract(body, r'id="showErrorTip"[^>]*>\s*([^<]*)');
      return (LoginResult.failed,
          err.trim().isNotEmpty ? err.trim() : '用户名或密码错误');
    }
    return (LoginResult.success, '');
  }

  /// 发送短信验证码
  Future<(bool, String)> sendSmsCode(String username) async {
    try {
      final r = await client.dio.post('$casBase/dynamicCode/getDynamicCodeByReauth.do',
          data: {
            'userName': username,
            'authCodeTypeName': 'reAuthDynamicCodeType',
          },
          options: Options(contentType: Headers.formUrlEncodedContentType));
      final map = jsonDecode(r.data.toString()) as Map<String, dynamic>;
      final res = map['res'].toString();
      if (res == 'success' || res == 'other_success' || res == 'code_time_fail') {
        return (true, map['mobile']?.toString() ?? '注册手机');
      }
      return (false, map['returnMessage']?.toString() ?? '发送失败');
    } catch (e) {
      return (false, e.toString());
    }
  }

  /// 提交短信验证码完成二次认证
  Future<(bool, String)> submitSmsCode(String username, String code) async {
    try {
      final r = await client.dio.post('$casBase/reAuthCheck/reAuthSubmit.do',
          data: {
            'service': service,
            'reAuthType': '3',
            'isMultifactor': 'true',
            'password': '',
            'dynamicCode': code,
            'uuid': '',
            'answer1': '',
            'answer2': '',
            'otpCode': '',
          },
          options: Options(contentType: Headers.formUrlEncodedContentType));
      final map = jsonDecode(r.data.toString()) as Map<String, dynamic>;
      if (map['code']?.toString() == 'reAuth_success') {
        return (true, '');
      }
      return (false, map['msg']?.toString() ?? '验证码错误');
    } catch (e) {
      return (false, e.toString());
    }
  }

  /// 二次认证通过后，用 CAS 票据进入教务系统
  /// 手动跟随重定向链：casLogin → sso.jsp?ticket → jwgl，逐步捕获 jwgl 的 JSESSIONID
  Future<(bool, String)> enterJwgl() async {
    try {
      final r = await client.getFollowRedirects(casLogin,
          queryParameters: {'service': service});
      final url = r.realUri.toString();
      // 仍停在 authserver，说明没拿到 ticket（登录态失效）
      if (url.contains('authserver')) {
        return (false, '登录态未建立，请重新登录');
      }
      // 访问教务主页，确认 jwgl 会话有效
      final home =
          await client.dio.get('$jwBase/jsxsd/framework/xsMain.htmlx');
      if (home.statusCode == 401) {
        return (false, '教务系统会话未建立，请重新登录');
      }
      return (true, url);
    } catch (e) {
      return (false, e.toString());
    }
  }
}
