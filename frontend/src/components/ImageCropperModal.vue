<template>
  <!-- 裁剪弹窗 — 仅 H5（PC 管理后台使用） -->
  <!-- #ifdef H5 -->
  <view class="cropper-overlay" v-if="visible" @touchmove.prevent.stop>
    <view class="cropper-modal">
      <!-- 标题栏 -->
      <view class="cropper-header">
        <text class="cropper-title">裁剪封面图（800×800）</text>
        <view class="cropper-close" @click="handleCancel">
          <text>✕</text>
        </view>
      </view>

      <!-- 裁剪区域 -->
      <view class="cropper-body">
        <view class="cropper-wrapper">
          <img
            ref="cropperImageRef"
            :src="imageUrl"
            class="cropper-image"
            crossorigin="anonymous"
            @load="initCropper"
            @error="handleImageError"
          />
        </view>

        <!-- 提示文字 -->
        <view class="cropper-tips">
          <text class="cropper-tips-icon">💡</text>
          <text class="cropper-tips-text">拖拽图片调整位置，裁剪后将生成 800×800 图片</text>
        </view>
      </view>

      <!-- 底部按钮 -->
      <view class="cropper-footer">
        <button class="cropper-btn cropper-btn--cancel" @click="handleCancel">
          取消
        </button>
        <button class="cropper-btn cropper-btn--confirm" @click="handleConfirm">
          确认裁剪
        </button>
      </view>
    </view>
  </view>
  <!-- #endif -->

  <!-- 非 H5 平台占位 -->
  <!-- #ifndef H5 -->
  <view v-if="visible">
    <text>裁剪功能仅支持 PC 浏览器</text>
  </view>
  <!-- #endif -->
</template>

<script setup>
import { ref, watch, onBeforeUnmount, nextTick } from 'vue'
import Cropper from 'cropperjs'
import 'cropperjs/dist/cropper.css'

// ========== Props ==========
const props = defineProps({
  /** 弹窗可见性 */
  visible: {
    type: Boolean,
    default: false,
  },
  /** 待裁剪图片 URL */
  imageUrl: {
    type: String,
    default: '',
  },
})

// ========== Emits ==========
const emit = defineEmits(['close', 'confirm', 'error'])

// ========== Refs ==========
const cropperImageRef = ref(null)

// ========== State ==========
let cropper = null
let initialized = false

// ========== Methods ==========

/**
 * 初始化 Cropper 实例
 * 在图片 load 事件后调用，确保 DOM 就绪
 */
function initCropper() {
  destroyCropper()

  const img = cropperImageRef.value
  if (!img) {
    console.warn('[ImageCropper] cropperImageRef is null')
    return
  }

  nextTick(() => {
    try {
      cropper = new Cropper(img, {
        aspectRatio: 1,              // 正方形 1:1
        viewMode: 1,                 // 裁剪框限制在图片范围内
        autoCropArea: 1,             // 裁剪框自动占满容器
        cropBoxMovable: false,       // 裁剪框不可移动
        cropBoxResizable: false,     // 裁剪框不可调整大小
        dragMode: 'move',            // 拖动图片模式
        guides: true,                // 显示九宫格辅助线
        background: true,            // 显示网格背景
        modal: true,                 // 裁剪框外半透明遮罩
        responsive: true,            // 响应容器大小变化
        restore: false,              // 不恢复上次裁剪
        center: true,                // 图片居中
        highlight: false,            // 不显示裁剪框内高亮
        movable: true,               // 图片可移动
        zoomable: true,              // 图片可缩放
        zoomOnWheel: true,           // 鼠标滚轮缩放
        zoomOnTouch: true,           // 触摸缩放
        rotatable: false,            // 不可旋转
        scalable: true,              // 图片可缩放
        toggleDragModeOnDblclick: false, // 双击不切换模式
      })
      initialized = true
    } catch (e) {
      console.error('[ImageCropper] Failed to init cropper:', e)
      emit('error', e)
    }
  })
}

/**
 * 确认裁剪
 * 生成 800×800 的 canvas → Blob → 通过 confirm 事件传出
 */
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

    canvas.toBlob(
      (blob) => {
        if (!blob) {
          uni.showToast({ title: '图片导出失败', icon: 'none' })
          return
        }
        emit('confirm', blob)
      },
      'image/jpeg',
      0.92
    )
  } catch (e) {
    console.error('[ImageCropper] Crop failed:', e)
    uni.showToast({ title: '裁剪失败', icon: 'none' })
  }
}

/**
 * 取消裁剪
 */
function handleCancel() {
  emit('close')
}

/**
 * 图片加载失败处理
 */
function handleImageError() {
  console.error('[ImageCropper] Image load error:', props.imageUrl)
  uni.showToast({ title: '图片加载失败', icon: 'none' })
  emit('error', new Error('Image load failed'))
}

/**
 * 销毁 Cropper 实例
 */
function destroyCropper() {
  if (cropper) {
    try {
      cropper.destroy()
    } catch (e) {
      console.warn('[ImageCropper] Destroy error:', e)
    }
    cropper = null
    initialized = false
  }
}

// ========== Watchers ==========

// 弹窗关闭时销毁 cropper
watch(
  () => props.visible,
  (newVal) => {
    if (!newVal) {
      destroyCropper()
    }
  }
)

// 更换图片时重新初始化
watch(
  () => props.imageUrl,
  (newVal, oldVal) => {
    if (newVal !== oldVal && cropper && initialized) {
      cropper.replace(newVal)
    }
  }
)

// ========== Lifecycle ==========
onBeforeUnmount(() => {
  destroyCropper()
})
</script>

<style scoped>
/* ========== 遮罩层 ========== */
.cropper-overlay {
  position: fixed;
  inset: 0;
  z-index: 9999;
  background: rgba(0, 0, 0, 0.5);
  display: flex;
  align-items: center;
  justify-content: center;
  animation: cropper-fade-in 0.2s ease;
}

@keyframes cropper-fade-in {
  from { opacity: 0; }
  to   { opacity: 1; }
}

/* ========== 弹窗容器 ========== */
.cropper-modal {
  background: #fff;
  border-radius: 8px;
  width: 720px;
  max-width: 95vw;
  max-height: 95vh;
  display: flex;
  flex-direction: column;
  box-shadow: 0 8px 32px rgba(0, 0, 0, 0.18);
  animation: cropper-slide-up 0.25s ease;
}

@keyframes cropper-slide-up {
  from { transform: translateY(30px); opacity: 0; }
  to   { transform: translateY(0);    opacity: 1; }
}

/* ========== 标题栏 ========== */
.cropper-header {
  display: flex;
  align-items: center;
  justify-content: space-between;
  padding: 20px 24px;
  border-bottom: 1px solid #eee;
  flex-shrink: 0;
}

.cropper-title {
  font-size: 16px;
  font-weight: 600;
  color: #333;
}

.cropper-close {
  width: 32px;
  height: 32px;
  display: flex;
  align-items: center;
  justify-content: center;
  border-radius: 4px;
  cursor: pointer;
  color: #999;
  font-size: 18px;
  transition: all 0.15s ease;
}

.cropper-close:hover {
  background: #f5f5f5;
  color: #333;
}

/* ========== 裁剪主体 ========== */
.cropper-body {
  flex: 1;
  display: flex;
  flex-direction: column;
  align-items: center;
  padding: 24px;
  overflow-y: auto;
  min-height: 0;
}

/* 裁剪容器 — 正方形 600×600 */
.cropper-wrapper {
  width: 600px;
  height: 600px;
  max-width: 100%;
  border: 1px solid #e0e0e0;
  border-radius: 4px;
  overflow: hidden;
  background: #fafafa;
  flex-shrink: 0;
}

.cropper-wrapper :deep(img) {
  display: block;
  max-width: 100%;
  max-height: 100%;
}

/* 提示文字 */
.cropper-tips {
  margin-top: 16px;
  display: flex;
  align-items: center;
  gap: 6px;
  flex-shrink: 0;
}

.cropper-tips-icon {
  font-size: 14px;
  line-height: 1;
}

.cropper-tips-text {
  font-size: 13px;
  color: #999;
  line-height: 1.5;
}

/* ========== 底部按钮栏 ========== */
.cropper-footer {
  display: flex;
  justify-content: flex-end;
  gap: 12px;
  padding: 16px 24px;
  border-top: 1px solid #eee;
  flex-shrink: 0;
}

/* 按钮通用样式 */
.cropper-btn {
  padding: 9px 24px;
  font-size: 14px;
  border-radius: 4px;
  border: 1px solid transparent;
  cursor: pointer;
  transition: all 0.15s ease;
  line-height: 1.5;
  outline: none;
  font-family: inherit;
}

/* 取消按钮 */
.cropper-btn--cancel {
  background: #fff;
  color: #666;
  border-color: #d9d9d9;
}

.cropper-btn--cancel:hover {
  color: #FF6B35;
  border-color: #FF6B35;
}

/* 确认按钮 */
.cropper-btn--confirm {
  background: #FF6B35;
  color: #fff;
  border-color: #FF6B35;
}

.cropper-btn--confirm:hover {
  background: #e55a2b;
  border-color: #e55a2b;
}

/* ========== 裁剪框内样式覆盖 ========== */
/* 裁剪框边框 — 白色虚线，更清晰 */
:deep(.cropper-crop-box) {
  outline: 2px dashed rgba(255, 255, 255, 0.9) !important;
}

/* 裁剪框角标 — 隐藏（不可调整大小） */
:deep(.cropper-point) {
  display: none !important;
}

/* 裁剪框中线 — 更明显 */
:deep(.cropper-dashed) {
  border-color: rgba(255, 255, 255, 0.4) !important;
}

/* 中心十字 */
:deep(.cropper-center) {
  display: block !important;
  opacity: 0.5;
}

/* ========== 响应式适配 ========== */
@media (max-width: 768px) {
  .cropper-modal {
    width: 95vw;
    max-height: 90vh;
  }

  .cropper-wrapper {
    width: 80vw;
    height: 80vw;
    max-width: 500px;
    max-height: 500px;
  }

  .cropper-header {
    padding: 16px 20px;
  }

  .cropper-body {
    padding: 20px;
  }

  .cropper-footer {
    padding: 12px 20px;
  }

  .cropper-title {
    font-size: 15px;
  }
}

@media (max-width: 480px) {
  .cropper-wrapper {
    width: 85vw;
    height: 85vw;
    max-width: 340px;
    max-height: 340px;
  }
}
</style>
