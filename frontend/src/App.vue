<script>export default{onLaunch(){},onShow(){}}</script>
<style>
page{background:#f5f5f5;font-family:-apple-system,BlinkMacSystemFont,'PingFang SC','Hiragino Sans GB','Microsoft YaHei',sans-serif}
/* uni-app 核心 CSS 把 uni-input 高度锁为 1.4em + overflow:hidden，
   导致 padding 撑出的区域无法点击。彻底覆盖，恢复正常输入框行为 */
uni-input,uni-textarea{height:auto!important;min-height:auto!important;overflow:visible!important}
</style>
