<template>
  <!-- ═══════════ H5: 完整裁剪功能 ═══════════ -->
  <!-- #ifdef H5 -->
  <view class="co-overlay" v-if="visible" @click.stop.prevent="() => {}" @touchmove.prevent.stop>
    <view class="co-dialog">
      <!-- ═══ 头部 ═══ -->
      <view class="co-header">
        <view class="co-header-left">
          <view class="co-header-icon">
            <svg width="18" height="18" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round">
              <rect x="2" y="6" width="20" height="12" rx="2"/>
              <path d="M8 2v4"/>
              <path d="M16 2v4"/>
              <rect x="2" y="18" width="9" height="4" rx="1"/>
              <path d="M22 22v-4"/>
            </svg>
          </view>
          <text class="co-title">裁剪封面图</text>
          <view class="co-badge">800 × 800</view>
        </view>
        <view class="co-close-btn" @click="handleCancel">
          <svg width="18" height="18" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round">
            <line x1="18" y1="6" x2="6" y2="18"/>
            <line x1="6" y1="6" x2="18" y2="18"/>
          </svg>
        </view>
      </view>

      <!-- ═══ 主体：左裁剪区 + 右预览 ═══ -->
      <view class="co-body">
        <!-- 左侧：裁剪操作区 -->
        <view class="co-main">
          <view class="co-crop-container" ref="wrapperRef">
            <img
              ref="cropperImageRef"
              :src="imageUrl"
              alt="封面原图"
              crossorigin="anonymous"
              @load="initCropper"
              @error="handleImageError"
            />
          </view>
        </view>

        <!-- 右侧：实时预览 -->
        <view class="co-preview">
          <view class="co-preview-header">
            <svg width="14" height="14" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round">
              <circle cx="11" cy="11" r="8"/><line x1="21" y1="21" x2="16.65" y2="16.65"/>
            </svg>
            <text class="co-preview-title">实时预览</text>
          </view>

          <view class="co-preview-cards">
            <!-- 圆形预览 -->
            <view class="co-preview-item">
              <view class="co-preview-box co-preview-box--round">
                <view class="co-preview-inner" id="previewRound"></view>
              </view>
              <text class="co-preview-label">圆形</text>
            </view>

            <!-- 方形预览 -->
            <view class="co-preview-item">
              <view class="co-preview-box co-preview-box--square">
                <view class="co-preview-inner" id="previewSquare"></view>
              </view>
              <text class="co-preview-label">方形</text>
            </view>
          </view>
        </view>
      </view>

      <!-- ═══ 底部工具栏 + 操作按钮 ═══ -->
      <view class="co-footer">
        <view class="co-toolbar">
          <!-- 缩放控制 -->
          <view class="co-tool-group">
            <button
              class="co-tool-btn co-tool-btn--icon"
              :disabled="zoomLevel <= 0.5"
              @click="zoomOut"
            >
              <svg width="14" height="14" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2.5">
                <line x1="5" y1="12" x2="19" y2="12"/>
              </svg>
            </button>
            <view class="co-zoom-display">
              <text class="co-zoom-value">{{ Math.round(zoomLevel * 100) }}</text>
              <text class="co-zoom-unit">%</text>
            </view>
            <button
              class="co-tool-btn co-tool-btn--icon"
              :disabled="zoomLevel >= 3"
              @click="zoomIn"
            >
              <svg width="14" height="14" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2.5">
                <line x1="12" y1="5" x2="12" y2="19"/>
                <line x1="5" y1="12" x2="19" y2="12"/>
              </svg>
            </button>
          </view>

          <!-- 分隔线 -->
          <view class="co-tool-divider"></view>

          <!-- 旋转控制 -->
          <view class="co-tool-group">
            <button class="co-tool-btn co-tool-btn--text" @click="rotateLeft">
              <svg width="14" height="14" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round">
                <polyline points="1 4 1 10 7 10"/>
                <path d="M3.51 15a9 9 0 1 0 2.13-9.36L1 10"/>
              </svg>
              <text>左转</text>
            </button>
            <button class="co-tool-btn co-tool-btn--text" @click="rotateRight">
              <svg width="14" height="14" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round">
                <polyline points="23 4 23 10 17 10"/>
                <path d="M20.49 15a9 9 0 1 1-2.12-9.36L23 10"/>
              </svg>
              <text>右转</text>
            </button>
          </view>

          <!-- 分隔线 -->
          <view class="co-tool-divider"></view>

          <!-- 重置 -->
          <button class="co-tool-btn co-tool-btn--text" @click="resetCrop">
            <svg width="14" height="14" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round">
              <path d="M1 4v6h6"/>
              <path d="M3.51 15a9 9 0 1 0 2.13-9.36L1 10"/>
              <path d="M17.5 9.5 12 15l-2.5-2.5"/>
              <line x1="12" y1="15" x2="12" y2="20"/>
            </svg>
            <text>重置</text>
          </button>
        </view>

        <!-- 操作按钮 -->
        <view class="co-actions">
          <button class="co-btn co-btn--cancel" @click="handleCancel">取消</button>
          <button class="co-btn co-btn--confirm" @click="handleConfirm">
            <svg width="16" height="16" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2.5" stroke-linecap="round" stroke-linejoin="round">
              <polyline points="20 6 9 17 4 12"/>
            </svg>
            <text>确认裁剪</text>
          </button>
        </view>
      </view>
    </view>
  </view>
  <!-- #endif -->

  <!-- ═══════════ 非 H5：不支持提示 ═══════════ -->
  <!-- #ifndef H5 -->
  <view v-if="visible" class="co-fallback">
    <view class="co-fallback-card">
      <text class="co-fallback-icon">✂️</text>
      <text class="co-fallback-title">裁剪功能</text>
      <text class="co-fallback-desc">请在 PC 浏览器中打开以使用图片裁剪功能</text>
      <button class="co-fallback-btn" @click="handleCancel">知道了</button>
    </view>
  </view>
  <!-- #endif -->
</template>

<script setup>
import { ref, watch, onBeforeUnmount, nextTick } from 'vue'
import Cropper from 'cropperjs'
import 'cropperjs/dist/cropper.css'

// ==================== Props & Emits ====================
const props = defineProps({
  visible: { type: Boolean, default: false },
  imageUrl: { type: String, default: '' },
})

const emit = defineEmits(['close', 'confirm', 'error'])

// ==================== Template Refs ====================
const cropperImageRef = ref(null)

// ==================== State ====================
let cropper = null
let initialized = false
const zoomLevel = ref(1)

// ==================== Preview Update ====================
function updatePreview() {
  if (!cropper || !initialized) return

  try {
    const roundCanvas = cropper.getCroppedCanvas({ width: 150, height: 150 })
    const squareCanvas = cropper.getCroppedCanvas({ width: 150, height: 150 })

    const roundEl = document.getElementById('previewRound')
    const squareEl = document.getElementById('previewSquare')

    if (roundEl) {
      roundEl.innerHTML = ''
      if (roundCanvas) roundEl.appendChild(roundCanvas)
    }
    if (squareEl) {
      squareEl.innerHTML = ''
      if (squareCanvas) squareEl.appendChild(squareCanvas)
    }
  } catch {
    /* silently skip preview errors */
  }
}

// ==================== Cropper Init ====================
function initCropper() {
  destroyCropper()

  const img = cropperImageRef.value
  if (!img) return

  nextTick(() => {
    try {
      cropper = new Cropper(img, {
        // Core constraints
        aspectRatio: 1,
        viewMode: 1,
        autoCropArea: 1,
        cropBoxMovable: false,
        cropBoxResizable: false,
        dragMode: 'move',

	        // 小图自动放大：canvas 最小 800×800 确保撑满容器
	        minCanvasWidth: 800,
	        minCanvasHeight: 800,

        // Visual
        guides: true,
        background: true,
        modal: true,
        center: true,
        highlight: false,

        // Interaction
        movable: true,
        zoomable: true,
        zoomOnWheel: true,
        zoomOnTouch: true,
        scalable: true,
        rotatable: true,
        toggleDragModeOnDblclick: false,
        responsive: true,
        restore: false,

        // Lifecycle
        ready() {
          // 动态计算填充缩放比 — 无论原图多大都撑满 800×800 容器
          const cd = cropper.getContainerData()
          const id = cropper.getImageData()
          if (id.naturalWidth > 0 && id.naturalHeight > 0) {
            const fillZoom = Math.max(
              cd.width / id.naturalWidth,
              cd.height / id.naturalHeight
            )
            cropper.zoomTo(fillZoom)
            zoomLevel.value = Math.round(fillZoom * 100) / 100
          } else {
            cropper.zoomTo(2)
            zoomLevel.value = 2
          }
          updatePreview()
        },

        crop() {
          updatePreview()
        },

        zoom(event) {
          zoomLevel.value = event.detail.ratio
        },
      })
      initialized = true
    } catch (e) {
      console.error('[ImageCropper] Init failed:', e)
      emit('error', e)
    }
  })
}

// ==================== Toolbar Actions ====================
function zoomIn() {
  if (!cropper) return
  cropper.zoom(0.1)
}

function zoomOut() {
  if (!cropper) return
  cropper.zoom(-0.1)
}

function rotateLeft() {
  if (!cropper) return
  cropper.rotate(-90)
}

function rotateRight() {
  if (!cropper) return
  cropper.rotate(90)
}

function resetCrop() {
  if (!cropper) return
  cropper.reset()
  // 重置后重新计算填充缩放比，确保小图仍撑满容器
  const cd = cropper.getContainerData()
  const id = cropper.getImageData()
  if (id.naturalWidth > 0 && id.naturalHeight > 0) {
    const fillZoom = Math.max(cd.width / id.naturalWidth, cd.height / id.naturalHeight)
    cropper.zoomTo(fillZoom)
    zoomLevel.value = Math.round(fillZoom * 100) / 100
  } else {
    cropper.zoomTo(2)
  }
}

// ==================== Confirm / Cancel ====================
function handleConfirm() {
  if (!cropper || !initialized) {
    uni.showToast({ title: '裁剪未就绪，请稍候', icon: 'none' })
    return
  }
  try {
    const canvas = cropper.getCroppedCanvas({
      width: 800,
      height: 800,
      imageSmoothingEnabled: true,
      imageSmoothingQuality: 'high',
    })
    if (!canvas) {
      uni.showToast({ title: '生成图片失败', icon: 'none' })
      return
    }
    canvas.toBlob((blob) => {
      if (!blob) {
        uni.showToast({ title: '图片导出失败', icon: 'none' })
        return
      }
      emit('confirm', blob)
    }, 'image/jpeg', 0.92)
  } catch (e) {
    console.error('[ImageCropper] Crop failed:', e)
    uni.showToast({ title: '裁剪失败', icon: 'none' })
  }
}

function handleCancel() {
  emit('close')
}

function handleImageError() {
  uni.showToast({ title: '图片加载失败', icon: 'none' })
  emit('error', new Error('Image load failed'))
}

// ==================== Destroy ====================
function destroyCropper() {
  if (cropper) {
    try { cropper.destroy() } catch {}
    cropper = null
    initialized = false
    zoomLevel.value = 1
  }
}

// ==================== Watchers ====================
watch(() => props.visible, (v) => { if (!v) destroyCropper() })
watch(() => props.imageUrl, (n, o) => {
  if (n !== o && cropper && initialized) cropper.replace(n)
})

// ==================== Lifecycle ====================
onBeforeUnmount(() => destroyCropper())
</script>

<style scoped>
/* ═══════════════════════════════════════════════
   1. OVERLAY
   ═══════════════════════════════════════════════ */
.co-overlay {
  position: fixed;
  inset: 0;
  z-index: 9999;
  background: rgba(0, 0, 0, 0.5);
  backdrop-filter: blur(6px);
  -webkit-backdrop-filter: blur(6px);
  display: flex;
  align-items: center;
  justify-content: center;
  animation: coFadeIn 0.2s ease;
}

@keyframes coFadeIn {
  from { opacity: 0; }
  to   { opacity: 1; }
}

/* ═══════════════════════════════════════════════
   2. DIALOG SHELL
   ═══════════════════════════════════════════════ */
.co-dialog {
  background: #ffffff;
  border-radius: 16px;
  box-shadow:
    0 0 0 1px rgba(0, 0, 0, 0.04),
    0 24px 64px rgba(0, 0, 0, 0.16),
    0 8px 24px rgba(0, 0, 0, 0.08);
  width: 1060px;
  max-width: 96vw;
  max-height: 95vh;
  display: flex;
  flex-direction: column;
  overflow: hidden;
  animation: coSlideUp 0.3s cubic-bezier(0.16, 1, 0.3, 1);
}

@keyframes coSlideUp {
  from { transform: translateY(24px); opacity: 0; }
  to   { transform: translateY(0);    opacity: 1; }
}

/* ═══════════════════════════════════════════════
   3. HEADER
   ═══════════════════════════════════════════════ */
.co-header {
  display: flex;
  align-items: center;
  justify-content: space-between;
  padding: 20px 28px;
  border-bottom: 1px solid #f0f0f0;
  flex-shrink: 0;
  background: #fafbfc;
}

.co-header-left {
  display: flex;
  align-items: center;
  gap: 10px;
}

.co-header-icon {
  width: 36px;
  height: 36px;
  border-radius: 10px;
  background: linear-gradient(135deg, #fff3ee 0%, #ffe8dc 100%);
  color: #FF6B35;
  display: flex;
  align-items: center;
  justify-content: center;
}

.co-title {
  font-size: 17px;
  font-weight: 650;
  color: #1a1a2e;
  letter-spacing: -0.01em;
}

.co-badge {
  font-size: 11px;
  font-weight: 600;
  color: #FF6B35;
  background: linear-gradient(135deg, #fff5f0 0%, #ffede4 100%);
  padding: 3px 10px;
  border-radius: 20px;
  letter-spacing: 0.02em;
  border: 1px solid #ffe8da;
}

.co-close-btn {
  width: 36px;
  height: 36px;
  border-radius: 10px;
  display: flex;
  align-items: center;
  justify-content: center;
  cursor: pointer;
  color: #94a3b8;
  transition: all 0.15s ease;
  background: transparent;
}
.co-close-btn:hover {
  background: #f1f5f9;
  color: #475569;
}
.co-close-btn:active {
  background: #e2e8f0;
  transform: scale(0.95);
}

/* ═══════════════════════════════════════════════
   4. BODY — left crop + right preview
   ═══════════════════════════════════════════════ */
.co-body {
  flex: 1;
  display: flex;
  gap: 28px;
  padding: 28px;
  background: linear-gradient(180deg, #f8f9fb 0%, #f5f6fa 100%);
  overflow: hidden;
  min-height: 0;
}

/* ── Left: crop area ── */
.co-main {
  flex: none;
  display: flex;
  align-items: center;
  justify-content: center;
}

.co-crop-container {
  width: 800px;
  height: 800px;
  background: #fff;
  border-radius: 12px;
  box-shadow:
    0 0 0 1px rgba(0, 0, 0, 0.04),
    0 4px 16px rgba(0, 0, 0, 0.06);
  overflow: hidden;
  flex-shrink: 0;
}

.co-crop-container :deep(img) {
  display: block;
  /* 不设 max-width/max-height，交给 Cropper ready() 中的 fillZoom 计算缩放比。
     小图会被放大至撑满 800×800 容器，大图由 Cropper responsive 自动适配。 */
}

/* ── Right: preview panel ── */
.co-preview {
  flex: none;
  width: 180px;
  display: flex;
  flex-direction: column;
  align-items: center;
  gap: 20px;
  padding-top: 4px;
}

.co-preview-header {
  display: flex;
  align-items: center;
  gap: 6px;
  color: #64748b;
}

.co-preview-title {
  font-size: 12px;
  font-weight: 650;
  color: #64748b;
  text-transform: uppercase;
  letter-spacing: 0.06em;
}

.co-preview-cards {
  display: flex;
  flex-direction: column;
  align-items: center;
  gap: 20px;
}

.co-preview-item {
  display: flex;
  flex-direction: column;
  align-items: center;
  gap: 8px;
}

.co-preview-box {
  width: 130px;
  height: 130px;
  background: #fff;
  box-shadow:
    0 0 0 1px rgba(0, 0, 0, 0.06),
    0 4px 12px rgba(0, 0, 0, 0.06);
  overflow: hidden;
  transition: all 0.25s ease;
}
.co-preview-box:hover {
  transform: translateY(-2px);
  box-shadow:
    0 0 0 1px rgba(0, 0, 0, 0.08),
    0 8px 24px rgba(0, 0, 0, 0.10);
}

.co-preview-box--round {
  border-radius: 50%;
}

.co-preview-box--square {
  border-radius: 12px;
}

.co-preview-inner {
  width: 100%;
  height: 100%;
}
.co-preview-inner :deep(canvas) {
  width: 100%;
  height: 100%;
}

.co-preview-label {
  font-size: 11px;
  font-weight: 550;
  color: #94a3b8;
  letter-spacing: 0.03em;
}

/* ═══════════════════════════════════════════════
   5. FOOTER — toolbar + action buttons
   ═══════════════════════════════════════════════ */
.co-footer {
  display: flex;
  align-items: center;
  justify-content: space-between;
  padding: 16px 28px;
  border-top: 1px solid #f0f0f0;
  background: #fff;
  flex-shrink: 0;
}

/* ── Toolbar ── */
.co-toolbar {
  display: flex;
  align-items: center;
  gap: 14px;
}

.co-tool-group {
  display: flex;
  align-items: center;
  gap: 2px;
}

.co-tool-divider {
  width: 1px;
  height: 22px;
  background: #e5e7eb;
  border-radius: 1px;
}

.co-zoom-display {
  display: flex;
  align-items: baseline;
  justify-content: center;
  min-width: 52px;
  padding: 2px 6px;
  background: #f8fafc;
  border-radius: 6px;
}

.co-zoom-value {
  font-size: 14px;
  font-weight: 700;
  color: #1e293b;
}

.co-zoom-unit {
  font-size: 10px;
  font-weight: 600;
  color: #94a3b8;
  margin-left: 1px;
}

/* ── Tool Buttons (base) ── */
.co-tool-btn {
  display: inline-flex;
  align-items: center;
  justify-content: center;
  gap: 4px;
  border: 1px solid #e2e8f0;
  background: #ffffff;
  border-radius: 8px;
  cursor: pointer;
  font-size: 13px;
  font-family: inherit;
  font-weight: 550;
  color: #475569;
  transition: all 0.15s ease;
  outline: none;
  padding: 7px 0;
  line-height: 1;
}
.co-tool-btn:hover {
  background: #f8fafc;
  border-color: #cbd5e1;
  color: #1e293b;
}
.co-tool-btn:active {
  background: #f1f5f9;
  border-color: #bcc5d1;
  transform: scale(0.97);
}
.co-tool-btn:disabled {
  opacity: 0.3;
  cursor: not-allowed;
  pointer-events: none;
}

.co-tool-btn--icon {
  width: 34px;
  height: 34px;
  padding: 0;
}

.co-tool-btn--text {
  padding: 7px 10px;
}

/* ── Action Buttons ── */
.co-actions {
  display: flex;
  gap: 10px;
}

.co-btn {
  display: inline-flex;
  align-items: center;
  gap: 6px;
  padding: 10px 24px;
  font-size: 14px;
  font-weight: 600;
  border-radius: 10px;
  border: none;
  cursor: pointer;
  transition: all 0.15s ease;
  outline: none;
  font-family: inherit;
  line-height: 1.4;
}

.co-btn--cancel {
  background: #f1f5f9;
  color: #475569;
  border: 1px solid #e2e8f0;
}
.co-btn--cancel:hover {
  background: #e2e8f0;
  color: #1e293b;
  border-color: #cbd5e1;
}
.co-btn--cancel:active {
  background: #cbd5e1;
  transform: scale(0.98);
}

.co-btn--confirm {
  background: linear-gradient(135deg, #FF6B35 0%, #e85d2c 100%);
  color: #ffffff;
  box-shadow: 0 2px 8px rgba(255, 107, 53, 0.3);
}
.co-btn--confirm:hover {
  box-shadow: 0 4px 16px rgba(255, 107, 53, 0.4);
  transform: translateY(-1px);
}
.co-btn--confirm:active {
  transform: translateY(0) scale(0.98);
  box-shadow: 0 1px 4px rgba(255, 107, 53, 0.25);
}

/* ═══════════════════════════════════════════════
   6. CROPPER.JS INTERNAL OVERRIDES
   ═══════════════════════════════════════════════ */
:deep(.cropper-crop-box) {
  outline: 2px dashed rgba(255, 255, 255, 0.85) !important;
}

:deep(.cropper-point) {
  display: none !important;
}

:deep(.cropper-dashed) {
  border-color: rgba(255, 255, 255, 0.4) !important;
}

:deep(.cropper-center) {
  display: block !important;
  opacity: 0.45;
}

:deep(.cropper-face) {
  opacity: 0 !important;
}

/* ═══════════════════════════════════════════════
   7. FALLBACK (non-H5)
   ═══════════════════════════════════════════════ */
.co-fallback {
  position: fixed;
  inset: 0;
  z-index: 9999;
  background: rgba(0, 0, 0, 0.5);
  display: flex;
  align-items: center;
  justify-content: center;
}

.co-fallback-card {
  background: #fff;
  border-radius: 16px;
  padding: 40px 32px;
  display: flex;
  flex-direction: column;
  align-items: center;
  gap: 12px;
  box-shadow: 0 16px 48px rgba(0, 0, 0, 0.15);
  width: 320px;
  max-width: 85vw;
}

.co-fallback-icon {
  font-size: 40px;
}

.co-fallback-title {
  font-size: 18px;
  font-weight: 700;
  color: #1e293b;
}

.co-fallback-desc {
  font-size: 14px;
  color: #64748b;
  text-align: center;
  line-height: 1.5;
}

.co-fallback-btn {
  margin-top: 8px;
  padding: 10px 32px;
  background: #FF6B35;
  color: #fff;
  border: none;
  border-radius: 10px;
  font-size: 14px;
  font-weight: 600;
  cursor: pointer;
}

/* ═══════════════════════════════════════════════
   8. RESPONSIVE
   ═══════════════════════════════════════════════ */
@media (max-width: 1100px) {
  .co-dialog {
    width: 97vw;
  }

  .co-body {
    flex-direction: column;
    align-items: center;
    overflow-y: auto;
    gap: 20px;
    padding: 20px;
  }

  .co-crop-container {
    width: 80vw;
    height: 80vw;
    max-width: 500px;
    max-height: 500px;
  }

  .co-preview {
    flex-direction: row;
    width: auto;
    gap: 24px;
    padding-top: 0;
  }

  .co-preview-cards {
    flex-direction: row;
    gap: 24px;
  }

  .co-preview-header { display: none; }

  .co-footer {
    flex-wrap: wrap;
    gap: 12px;
  }
}

@media (max-width: 640px) {
  .co-header { padding: 14px 16px; }
  .co-title  { font-size: 15px; }

  .co-body   { padding: 16px; }

  .co-crop-container {
    width: 88vw;
    height: 88vw;
    max-width: 340px;
    max-height: 340px;
  }

  .co-preview-cards { gap: 16px; }

  .co-preview-box {
    width: 90px;
    height: 90px;
  }

  .co-toolbar { gap: 8px; }
  .co-tool-group { gap: 1px; }
  .co-tool-btn--text { padding: 6px 8px; font-size: 12px; }
  .co-tool-btn--icon { width: 30px; height: 30px; }

  .co-footer { padding: 12px 16px; }
  .co-btn { padding: 8px 16px; font-size: 13px; }
}

@media (max-width: 400px) {
  .co-header { padding: 10px 12px; }
  .co-header-icon { width: 30px; height: 30px; border-radius: 8px; }
  .co-title { font-size: 14px; }
  .co-badge { font-size: 10px; padding: 2px 7px; }

  .co-crop-container {
    width: 92vw;
    height: 92vw;
    max-width: 280px;
    max-height: 280px;
  }

  .co-preview-cards { gap: 10px; }
  .co-preview-box {
    width: 70px;
    height: 70px;
  }
  .co-preview-label { font-size: 10px; }

  .co-toolbar { gap: 4px; }
  .co-tool-btn--text { padding: 5px 6px; font-size: 11px; }
  .co-btn { padding: 7px 14px; font-size: 12px; }
}
</style>
