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

    // Handle WeChat Official Account custom menu redirects.
    // Custom menus don't support hash fragments (e.g. #/pages/employee/login),
    // so we use query params: ?from=employee or ?route=pages/employee/login
    this.handleDeepLink()
  },
  onShow() {},
  methods: {
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
