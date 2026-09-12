package com.manzili.hai.export

import com.manzili.hai.engine.PolygonGeometryEngine
import com.manzili.hai.model.FloorPlan

object SvgPlanExporter {
    fun render(input: FloorPlan): String {
        val plan = PolygonGeometryEngine.normalize(input)
        fun sx(v: Float) = 60.0 + 1080.0 * v / 100.0
        fun sy(v: Float) = 100.0 + 680.0 * v / 100.0
        fun f(v: Double) = "%.1f".format(v)
        fun esc(v: String) = v.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")
        return buildString {
            append("<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n")
            append("<svg xmlns=\"http://www.w3.org/2000/svg\" width=\"1200\" height=\"850\" viewBox=\"0 0 1200 850\">\n")
            append("<rect width=\"1200\" height=\"850\" fill=\"white\"/>\n")
            append("<text x=\"60\" y=\"45\" font-size=\"26\" font-family=\"sans-serif\" font-weight=\"700\">${esc(plan.title)}</text>\n")
            append("<text x=\"60\" y=\"70\" font-size=\"13\" font-family=\"sans-serif\" fill=\"#666\">V${plan.revision} • scale ${plan.scaleConfidence}%</text>\n")
            append("<rect x=\"60\" y=\"100\" width=\"1080\" height=\"680\" fill=\"none\" stroke=\"#9A7447\"/>\n")
            plan.rooms.forEach { room ->
                if (room.polygon.size >= 3) {
                    val pts = room.polygon.joinToString(" ") { "${f(sx(it.x))},${f(sy(it.y))}" }
                    append("<polygon points=\"$pts\" fill=\"#F7F4EE\" stroke=\"#9A7447\" stroke-width=\"1.4\"/>\n")
                    val cx = room.polygon.map { sx(it.x) }.average()
                    val cy = room.polygon.map { sy(it.y) }.average()
                    append("<text x=\"${f(cx)}\" y=\"${f(cy)}\" text-anchor=\"middle\" font-size=\"13\" font-family=\"sans-serif\">${esc(room.name)}</text>\n")
                }
            }
            plan.walls.forEach { w -> append("<line x1=\"${f(sx(w.start.x))}\" y1=\"${f(sy(w.start.y))}\" x2=\"${f(sx(w.end.x))}\" y2=\"${f(sy(w.end.y))}\" stroke=\"#20211E\" stroke-width=\"3\"/>\n") }
            plan.openings.forEach { o -> append("<circle cx=\"${f(sx(o.x))}\" cy=\"${f(sy(o.y))}\" r=\"5\" fill=\"white\" stroke=\"#64756B\" stroke-width=\"2\"/>\n") }
            append("</svg>")
        }
    }
}
