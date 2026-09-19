/// 应用设置模型
class AppSettings {
  // 登录
  String username;
  String password;

  // 自定义标语
  String slogan;

  // 提醒
  bool reminderEnabled; // 是否开启上课提醒
  int reminderAdvanceMinutes; // 提前多少分钟提醒
  int reminderMethod; // 0=通知栏 1=闹钟式(高优先级) 2=仅震动

  // 自动更新
  bool autoUpdate; // 是否自动更新
  int autoUpdateMinutes; // 自动更新间隔（分钟）

  // 学期信息
  String schoolStartDate; // 开学日期（第一周周一），格式 yyyy-MM-dd

  // 周视图定制
  double fontSize; // 字体大小
  int alignment; // 0=居中 1=居左 2=分散对齐
  double gridCellHeight; // 每节课高度
  double gridSpacing; // 格子间隔
  bool useDefaultColors; // 使用默认配色
  List<int> customColors; // 自定义颜色（ARGB int 列表）
  bool showOtherWeeks; // 显示非本周课程

  AppSettings({
    this.username = '',
    this.password = '',
    this.slogan = '学而不思则罔，思而不学则殆',
    this.reminderEnabled = false,
    this.reminderAdvanceMinutes = 10,
    this.reminderMethod = 0,
    this.autoUpdate = true,
    this.autoUpdateMinutes = 60,
    this.schoolStartDate = '',
    this.fontSize = 13,
    this.alignment = 0,
    this.gridCellHeight = 64,
    this.gridSpacing = 4,
    this.useDefaultColors = true,
    List<int>? customColors,
    this.showOtherWeeks = true,
  }) : customColors = customColors ?? [];

  Map<String, dynamic> toJson() => {
        'username': username,
        'password': password,
        'slogan': slogan,
        'reminderEnabled': reminderEnabled,
        'reminderAdvanceMinutes': reminderAdvanceMinutes,
        'reminderMethod': reminderMethod,
        'autoUpdate': autoUpdate,
        'autoUpdateMinutes': autoUpdateMinutes,
        'schoolStartDate': schoolStartDate,
        'fontSize': fontSize,
        'alignment': alignment,
        'gridCellHeight': gridCellHeight,
        'gridSpacing': gridSpacing,
        'useDefaultColors': useDefaultColors,
        'customColors': customColors,
        'showOtherWeeks': showOtherWeeks,
      };

  factory AppSettings.fromJson(Map<String, dynamic> j) => AppSettings(
        username: j['username'] ?? '',
        password: j['password'] ?? '',
        slogan: j['slogan'] ?? '学而不思则罔，思而不学则殆',
        reminderEnabled: j['reminderEnabled'] ?? false,
        reminderAdvanceMinutes: j['reminderAdvanceMinutes'] ?? 10,
        reminderMethod: j['reminderMethod'] ?? 0,
        autoUpdate: j['autoUpdate'] ?? true,
        autoUpdateMinutes: j['autoUpdateMinutes'] ?? 60,
        schoolStartDate: j['schoolStartDate'] ?? '',
        fontSize: (j['fontSize'] ?? 13).toDouble(),
        alignment: j['alignment'] ?? 0,
        gridCellHeight: (j['gridCellHeight'] ?? 64).toDouble(),
        gridSpacing: (j['gridSpacing'] ?? 4).toDouble(),
        useDefaultColors: j['useDefaultColors'] ?? true,
        customColors: List<int>.from(j['customColors'] ?? []),
        showOtherWeeks: j['showOtherWeeks'] ?? true,
      );

  AppSettings copy() => AppSettings.fromJson(toJson());
}
