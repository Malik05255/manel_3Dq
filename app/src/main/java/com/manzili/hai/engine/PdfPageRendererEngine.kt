package com.manzili.hai.engine

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.pdf.PdfRenderer
import android.net.Uri
import android.util.Base64
import java.io.ByteArrayOutputStream

/** Renders image/PDF evidence page-by-page without OCR. PDF pages are capped deliberately for mobile memory/network safety. */
class PdfPageRendererEngine(private val context: Context) {
    data class PageImage(val pageIndex:Int,val base64Jpeg:String,val width:Int,val height:Int)

    fun isPdf(uri:Uri):Boolean = context.contentResolver.getType(uri).orEmpty().equals("application/pdf",true)

    fun render(uri:Uri,maxPdfPages:Int=5,targetMaxPx:Int=1800,jpegQuality:Int=88):List<PageImage> {
        val maxPages=maxPdfPages.coerceIn(1,8)
        return if(isPdf(uri)) renderPdf(uri,maxPages,targetMaxPx,jpegQuality) else listOf(renderImage(uri,targetMaxPx,jpegQuality))
    }

    fun pageCount(uri:Uri):Int {
        if(!isPdf(uri)) return 1
        val pfd=context.contentResolver.openFileDescriptor(uri,"r")?:error("تعذر فتح PDF")
        return PdfRenderer(pfd).use { it.pageCount }
    }

    private fun renderPdf(uri:Uri,maxPages:Int,targetMaxPx:Int,quality:Int):List<PageImage> {
        val pfd=context.contentResolver.openFileDescriptor(uri,"r")?:error("تعذر فتح PDF")
        return PdfRenderer(pfd).use { renderer ->
            require(renderer.pageCount>0){"PDF بلا صفحات"}
            val count=minOf(renderer.pageCount,maxPages)
            buildList {
                for(index in 0 until count) {
                    renderer.openPage(index).use { page ->
                        val scale=minOf(targetMaxPx.toFloat()/page.width.coerceAtLeast(1),targetMaxPx.toFloat()/page.height.coerceAtLeast(1),1.8f)
                        val w=(page.width*scale).toInt().coerceAtLeast(1)
                        val h=(page.height*scale).toInt().coerceAtLeast(1)
                        val bitmap=Bitmap.createBitmap(w,h,Bitmap.Config.ARGB_8888)
                        page.render(bitmap,null,null,PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
                        add(PageImage(index,encode(bitmap,quality),w,h))
                        bitmap.recycle()
                    }
                }
            }
        }
    }

    private fun renderImage(uri:Uri,targetMaxPx:Int,quality:Int):PageImage {
        val source=context.contentResolver.openInputStream(uri).use { BitmapFactory.decodeStream(it)?:error("تعذر قراءة الصورة") }
        val ratio=minOf(targetMaxPx.toFloat()/source.width.coerceAtLeast(1),targetMaxPx.toFloat()/source.height.coerceAtLeast(1),1f)
        val bitmap=if(ratio<.999f) Bitmap.createScaledBitmap(source,(source.width*ratio).toInt().coerceAtLeast(1),(source.height*ratio).toInt().coerceAtLeast(1),true) else source
        val result=PageImage(0,encode(bitmap,quality),bitmap.width,bitmap.height)
        if(bitmap!==source) bitmap.recycle()
        source.recycle()
        return result
    }

    private fun encode(bitmap:Bitmap,quality:Int):String {
        val out=ByteArrayOutputStream()
        bitmap.compress(Bitmap.CompressFormat.JPEG,quality.coerceIn(65,95),out)
        return Base64.encodeToString(out.toByteArray(),Base64.NO_WRAP)
    }
}
