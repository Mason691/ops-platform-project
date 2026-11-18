/**
 * H5 图片压缩：统一重编码 JPEG，最长边默认 2000px。
 * 收据 OCR 需要文字清晰度，因此不压得过小；服务端仍会再做一次压缩兜底。
 */
export function compressImageBlob(blob, maxDim = 2000, quality = 0.85) {
  if (!blob) return Promise.resolve(blob)
  if (typeof document === 'undefined' || typeof Image === 'undefined') {
    return Promise.resolve(blob)
  }
  return new Promise(resolve => {
    const objectUrl = URL.createObjectURL(blob)
    const img = new Image()
    img.onload = () => {
      try {
        const scale = Math.min(1, maxDim / Math.max(img.width, img.height))
        const canvas = document.createElement('canvas')
        canvas.width = Math.max(1, Math.round(img.width * scale))
        canvas.height = Math.max(1, Math.round(img.height * scale))
        const ctx = canvas.getContext('2d')
        ctx.fillStyle = '#fff'
        ctx.fillRect(0, 0, canvas.width, canvas.height)
        ctx.drawImage(img, 0, 0, canvas.width, canvas.height)
        canvas.toBlob(out => {
          URL.revokeObjectURL(objectUrl)
          resolve(out && out.size < blob.size ? out : blob)
        }, 'image/jpeg', quality)
      } catch (e) {
        URL.revokeObjectURL(objectUrl)
        resolve(blob)
      }
    }
    img.onerror = () => {
      URL.revokeObjectURL(objectUrl)
      resolve(blob)
    }
    img.src = objectUrl
  })
}
