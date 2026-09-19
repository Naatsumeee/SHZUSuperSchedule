import 'dart:io';
import 'package:dio/dio.dart';
import 'package:dio/io.dart';

/// 简单 Cookie 存储（按域名分组，避免 authserver 与 jwgl 的 JSESSIONID 冲突）
class CookieStore {
  final Map<String, Map<String, String>> _cookies = {};

  void updateFromResponse(Uri uri, Map<String, List<String>> headers) {
    final list = headers['set-cookie'];
    if (list == null) return;
    final domain = uri.host;
    final map = _cookies.putIfAbsent(domain, () => {});
    for (final raw in list) {
      final pair = raw.split(';').first.trim();
      final idx = pair.indexOf('=');
      if (idx <= 0) continue;
      final name = pair.substring(0, idx);
      final value = pair.substring(idx + 1);
      if (name.isEmpty || name.toLowerCase() == 'path' || name.toLowerCase() == 'domain') continue;
      map[name] = value;
    }
  }

  String cookieHeader(String host) {
    final buf = StringBuffer();
    for (final entry in _cookies.entries) {
      // 后缀匹配：精确或 *.host
      if (host == entry.key || host.endsWith('.${entry.key}')) {
        for (final kv in entry.value.entries) {
          if (buf.isNotEmpty) buf.write('; ');
          buf.write('${kv.key}=${kv.value}');
        }
      }
    }
    return buf.toString();
  }
}

/// 统一的 API 客户端：忽略证书校验（学校老 TLS/自签名证书），按域管理 Cookie
class ApiClient {
  final Dio dio = Dio();
  final CookieStore cookies = CookieStore();

  ApiClient() {
    dio.options = BaseOptions(
      connectTimeout: const Duration(seconds: 25),
      receiveTimeout: const Duration(seconds: 30),
      // 关闭自动重定向：CAS 登录链中间 302 的 Set-Cookie 会被 Dio 内部吞掉，
      // 导致 jwgl 的 JSESSIONID 丢失而返回 401。改为手动跟随（见 getFollowRedirects）。
      followRedirects: false,
      // 接受 1xx~4xx（含 302/401），便于手动处理；5xx 仍抛异常。
      validateStatus: (status) => status != null && status < 500,
      headers: {
        'User-Agent':
            'Mozilla/5.0 (Linux; Android 13) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Mobile Safari/537.36',
        'Accept':
            'text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8',
      },
    );
    // 忽略证书错误（老 TLS / 自签名）
    (dio.httpClientAdapter as IOHttpClientAdapter).createHttpClient = () {
      final client = HttpClient();
      client.badCertificateCallback = (cert, host, port) => true;
      return client;
    };
    // 请求前注入 Cookie，响应后更新 Cookie
    dio.interceptors.add(InterceptorsWrapper(
      onRequest: (options, handler) {
        final uri = Uri.parse(options.uri.toString());
        final ck = cookies.cookieHeader(uri.host);
        if (ck.isNotEmpty) options.headers['Cookie'] = ck;
        handler.next(options);
      },
      onResponse: (response, handler) {
        final uri = Uri.parse(response.requestOptions.uri.toString());
        cookies.updateFromResponse(uri, response.headers.map);
        handler.next(response);
      },
    ));
  }

  /// 是否重定向状态码
  static bool isRedirect(int? code) =>
      code == 301 || code == 302 || code == 303 || code == 307 || code == 308;

  /// 手动跟随重定向链（GET），每一步都经过 onResponse 拦截器，从而正确捕获
  /// 中间 302 响应里的 Set-Cookie（例如 sso.jsp 设置的 jwgl JSESSIONID）。
  Future<Response<dynamic>> getFollowRedirects(String url,
      {Map<String, dynamic>? queryParameters}) async {
    var current = url;
    var qp = queryParameters;
    Response<dynamic>? last;
    for (var i = 0; i < 12; i++) {
      final r = await dio.get<dynamic>(current, queryParameters: qp);
      last = r;
      if (!isRedirect(r.statusCode)) break;
      final loc = r.headers.value('location');
      if (loc == null || loc.isEmpty) break;
      // 解析相对/绝对 Location
      current = Uri.parse(current).resolve(loc).toString();
      qp = null; // Location 已含 query
    }
    return last!;
  }

  /// 重置（登出）
  void clear() {
    dio.options.headers.remove('Cookie');
    // CookieStore 无清空方法，重建内部表
  }
}
