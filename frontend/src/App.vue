<script>
export default {
  onLaunch() {
    // Auto-restore login: validate stored token on app startup
    const token = uni.getStorageSync('ops_token')
    if (token) {
      uni.request({
        url: '/api/ops/user/me',
        method: 'GET',
        header: { 'Authorization': 'Bearer ' + token },
        success(res) {
          if (res.data && res.data.code === 200) {
            uni.setStorageSync('ops_member', JSON.stringify(res.data.data))
          }
        },
        fail() { /* offline — keep existing state */ }
      })
    }

    // 微信网页授权回调优先：微信会在 /ops/ 后追加 ?code=...&state=...
    // 先落盘并立即清理地址栏，再进入回调页；避免 code 留在历史记录/分享链接中。
    if (this.handleWechatOAuthCallback()) return

    // Handle WeChat Official Account custom menu redirects.
    // Custom menus don't support hash fragments (e.g. #/pages/employee/login),
    // so we use query params: ?from=employee or ?route=pages/employee/login
    this.handleDeepLink()
  },
  onShow() {},
  methods: {
    handleWechatOAuthCallback() {
      // #ifdef H5
      try {
        const params = new URLSearchParams(window.location.search)
        const code = params.get('code')
        const state = params.get('state')
        if (!code || !state) return false
        uni.setStorageSync('ops_wechat_oauth_code', code)
        uni.setStorageSync('ops_wechat_oauth_state', state)
        try {
          window.history.replaceState(
            {},
            document.title,
            window.location.pathname + window.location.hash
          )
        } catch (_) { /* ignore — 部分微信 WebView 限制 history API */ }
        // 延迟到首屏页面栈就绪后再切换，避免 onLaunch 阶段 reLaunch 被忽略
        setTimeout(() => {
          uni.reLaunch({ url: '/pages/employee/wechat-callback' })
        }, 0)
        return true
      } catch (_) {
        return false
      }
      // #endif
      return false
    },
    handleDeepLink() {
      try {
        const params = new URLSearchParams(window.location.search)
        const route = params.get('route')
        const from = params.get('from')

        if (route) {
          // Direct route: ?route=pages/employee/login
          uni.navigateTo({ url: '/' + route })
          return
        }

        if (from === 'employee') {
          const empToken = uni.getStorageSync('ops_employee_token')
          if (empToken) {
            uni.redirectTo({ url: '/pages/employee/dashboard' })
          } else {
            uni.redirectTo({ url: '/pages/employee/login' })
          }
        }
      } catch (_) { /* ignore — non-browser env or parsing error */ }
    }
  }
}
</script>
<style>
page{background:#f5f5f5;font-family:-apple-system,BlinkMacSystemFont,'PingFang SC','Hiragino Sans GB','Microsoft YaHei',sans-serif}
/* uni-app 核心 CSS 把 uni-input 高度锁为 1.4em + overflow:hidden，
   导致 padding 撑出的区域无法点击。彻底覆盖，恢复正常输入框行为 */
uni-input,uni-textarea{height:auto!important;min-height:auto!important;overflow:visible!important}
</style>
