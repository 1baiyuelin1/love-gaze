'use strict';

// Presentation examples for the prototype. Confirmed actions need their own
// observed event in a native implementation; opening an app does not imply one.
const APP_BEHAVIORS = {
  wechat:{open:'微信选手上线。',actions:[
    {id:'message',label:'发出消息',line:'在微信发了消息，键盘完成一轮输出。'},
    {id:'call',label:'发起通话',line:'用微信发起了通话，切换到嘴聊模式。'}]},
  qq:{open:'企鹅开始营业。',actions:[
    {id:'message',label:'发出消息',line:'在 QQ 发了消息，企鹅送信去了。'},
    {id:'call',label:'发起通话',line:'用 QQ 发起了通话，打字先下班。'}]},
  meituan:{open:'今天吃啥，又是个难题。',actions:[
    {id:'browse_food',label:'浏览外卖商家',line:'在美团看外卖，正在解决“吃啥”难题。'},
    {id:'place_order',label:'外卖下单成功',line:'在美团点好外卖，开启等饭模式。'}]},
  taobao:{open:'钱包先冷静。',actions:[
    {id:'add_cart',label:'加入购物车',line:'往淘宝购物车加了东西，钱包表示收到。'},
    {id:'place_order',label:'下单成功',line:'在淘宝下了单，进入等快递模式。'}]},
  jd:{open:'先逛逛，钱包别紧张。',actions:[
    {id:'add_cart',label:'加入购物车',line:'京东购物车更新了，钱包提高警惕。'},
    {id:'place_order',label:'下单成功',line:'在京东下了单，快递待收清单加一。'}]},
  redbook:{open:'种草雷达预备。',actions:[
    {id:'view_note',label:'浏览笔记',line:'在小红书看笔记，种草雷达启动。'},
    {id:'publish_note',label:'发布笔记',line:'在小红书发了笔记，轮到自己种草。'}]},
  douyin:{open:'手指准备上班。',actions:[
    {id:'watch_video',label:'观看视频',line:'刷起了抖音，手指开始加班。'},
    {id:'like_video',label:'点赞视频',line:'在抖音点了赞，拇指投出一票。'}]},
  hongguo:{open:'短剧选手就位。',actions:[
    {id:'watch_drama',label:'观看短剧',line:'看起了红果短剧，追剧模式启动。'},
    {id:'favorite_drama',label:'收藏短剧',line:'在红果收藏了短剧，下次接着追。'}]},
  music:{open:'耳朵准备上班。',actions:[
    {id:'play_music',label:'播放音乐',line:'网易云开始放歌，耳朵开工。'},
    {id:'pause_music',label:'暂停音乐',line:'暂停了网易云音乐，耳朵休息一下。'}]},
  browser:{open:'准备查点东西。',actions:[
    {id:'search',label:'进行搜索',line:'用浏览器搜了点东西，求知欲上线。'},
    {id:'view_page',label:'浏览网页',line:'打开了一个网页，网上溜达一下。'}]},
  bilibili:{open:'B 站选手上线。',actions:[
    {id:'watch_video',label:'观看视频',line:'在 B 站看起了视频，快乐加载完成。'},
    {id:'favorite_video',label:'收藏视频',line:'收藏了 B 站视频，先码住再说。'}]},
  alipay:{open:'钱包进入工作状态。',actions:[
    {id:'payment',label:'付款成功',line:'支付宝付款完成，钱包瘦了一点。'},
    {id:'scan',label:'打开扫一扫',line:'打开了支付宝扫一扫，准备对个码。'}]},
  amap:{open:'方向感外包一下。',actions:[
    {id:'navigation',label:'开始导航',line:'高德导航已开始，方向感交给专业的。'},
    {id:'ride',label:'叫车成功',line:'用高德叫了车，等车模式启动。'}]},
  deepseek:{open:'外脑准备上班。',actions:[
    {id:'ask',label:'发起提问',line:'向 DeepSeek 提了个问题，外脑开始上班。'},
    {id:'response',label:'收到回答',line:'DeepSeek 回复了，外脑交作业了。'}]},
  doubao:{open:'AI 搭子已就位。',actions:[
    {id:'ask',label:'发起对话',line:'和豆包聊上了，AI 搭子开始接话。'},
    {id:'generate_image',label:'生成图片',line:'用豆包生成了图片，画画这事交给 AI。'}]},
  zhihu:{open:'知识区选手上线。',actions:[
    {id:'read_answer',label:'阅读回答',line:'在知乎看回答，知识量正在加码。'},
    {id:'post_answer',label:'发布回答',line:'在知乎发了回答，轮到自己支招。'}]},
  quark:{open:'搜索工具人就位。',actions:[
    {id:'search',label:'进行搜索',line:'用夸克搜了点东西，答案正在赶来。'},
    {id:'view_page',label:'浏览网页',line:'在夸克看起了网页，网上逛一圈。'}]},
  camera:{open:'镜头准备开工。',actions:[
    {id:'photo',label:'拍摄照片',line:'用相机拍了张照片，快门完成一次打卡。'},
    {id:'video',label:'开始录像',line:'用相机开始录像，记录模式开工。'}]},
  cainiao:{open:'快递雷达预备。',actions:[
    {id:'track_parcel',label:'查看包裹',line:'在菜鸟看了包裹，快递雷达启动。'},
    {id:'pickup',label:'取件成功',line:'菜鸟取件完成，包裹成功归队。'}]},
  baidudisk:{open:'云端仓库开门。',actions:[
    {id:'upload',label:'上传文件',line:'往百度网盘传了文件，云端仓库补货。'},
    {id:'download',label:'下载文件',line:'用百度网盘下载文件，资料往本地搬。'}]},
};

const PLAIN_BEHAVIOR={actions:[]};
const isPresetApp=app=>Object.hasOwn(APP_BEHAVIORS,app.id);
function behaviorFor(app) {return APP_BEHAVIORS[app.id] || PLAIN_BEHAVIOR;}
function openedCopy(app) {return isPresetApp(app) ? `打开了${app.name}，${behaviorFor(app).open}` : `打开应用：${app.name}`;}
function actionFor(app,id) {return behaviorFor(app).actions.find(action=>action.id === id);}
function actionCopy(app,id) {return actionFor(app,id).line;}
