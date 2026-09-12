package com.manzili.hai.export

import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.graphics.pdf.PdfDocument
import com.manzili.hai.engine.PolygonGeometryEngine
import com.manzili.hai.model.FloorPlan
import java.io.OutputStream

object PdfPlanExporter {
    fun write(input: FloorPlan, output: OutputStream) {
        val plan = PolygonGeometryEngine.normalize(input)
        val doc = PdfDocument()
        val page = doc.startPage(PdfDocument.PageInfo.Builder(842, 595, 1).create())
        val canvas = page.canvas
        canvas.drawColor(Color.WHITE)
        val wallPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.rgb(32, 33, 30); strokeWidth = 2f; style = Paint.Style.STROKE }
        val edgePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.rgb(154, 116, 71); strokeWidth = 1.2f; style = Paint.Style.STROKE }
        val fillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.rgb(247, 244, 238); style = Paint.Style.FILL }
        val titlePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.rgb(32, 33, 30); textSize = 20f; isFakeBoldText = true }
        val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.rgb(60, 62, 58); textSize = 10f }
        val smallPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.rgb(95, 96, 92); textSize = 8f }
        val frame = RectF(44f, 78f, 798f, 535f)
        fun x(v: Float) = frame.left + frame.width() * v / 100f
        fun y(v: Float) = frame.top + frame.height() * v / 100f

        canvas.drawText(plan.title, 44f, 38f, titlePaint)
        val dims = if (plan.widthM != null && plan.heightM != null) "${fmt(plan.widthM)}m × ${fmt(plan.heightM)}m" else "Scale not confirmed"
        canvas.drawText("Manzili HAI • $dims • V${plan.revision} • scale ${plan.scaleConfidence}%", 44f, 56f, smallPaint)
        canvas.drawRect(frame, edgePaint)

        plan.rooms.forEach { room ->
            if (room.polygon.size >= 3) {
                val path = Path().apply {
                    moveTo(x(room.polygon[0].x), y(room.polygon[0].y))
                    room.polygon.drop(1).forEach { lineTo(x(it.x), y(it.y)) }
                    close()
                }
                canvas.drawPath(path, fillPaint)
                canvas.drawPath(path, edgePaint)
                val cx = room.polygon.map { x(it.x) }.average().toFloat()
                val cy = room.polygon.map { y(it.y) }.average().toFloat()
                canvas.drawText(room.name, cx - textPaint.measureText(room.name) / 2f, cy, textPaint)
                if (room.areaM2 > 0) {
                    val a = "${fmt(room.areaM2)}m²"
                    canvas.drawText(a, cx - smallPaint.measureText(a) / 2f, cy + 12f, smallPaint)
                }
            }
        }

        plan.walls.forEach { canvas.drawLine(x(it.start.x), y(it.start.y), x(it.end.x), y(it.end.y), wallPaint) }
        plan.openings.forEach { opening ->
            val px = x(opening.x); val py = y(opening.y)
            if (opening.type.contains("window", true) || opening.type.contains("ناف")) canvas.drawLine(px - 5f, py, px + 5f, py, edgePaint)
            else canvas.drawCircle(px, py, 4f, edgePaint)
        }

        canvas.drawText("Canonical HAI geometry • review professional/code requirements before construction", 44f, 578f, smallPaint)
        doc.finishPage(page)
        doc.writeTo(output)
        doc.close()
    }

    private fun fmt(value: Double) = "%.2f".format(value)
}
