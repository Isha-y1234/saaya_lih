package com.project.saaya.data.repository

import android.graphics.Color
import android.graphics.Typeface
import android.graphics.pdf.PdfDocument
import android.text.Layout
import android.text.StaticLayout
import android.text.TextPaint
import com.project.saaya.data.remote.IncidentReport
import java.io.File

/** A4, paginated native text layout (including system Hindi shaping); no server PDF processing. */
object IncidentPdf {
    fun generate(report: IncidentReport, target: File) {
        val document=PdfDocument()
        val paint=TextPaint().apply { color=Color.rgb(28,35,45);textSize=11f;typeface=Typeface.create("sans-serif",Typeface.NORMAL) }
        val footer=TextPaint().apply {textSize=9f;color=Color.DKGRAY}
        var number=0
        var page: PdfDocument.Page?=null
        var y=48f
        fun finish() {
            page?.let {
                it.canvas.drawText("Saaya · ${report.incident.id} · Page $number",40f,816f,footer)
                document.finishPage(it)
            }
        }
        fun start() { number++;page=document.startPage(PdfDocument.PageInfo.Builder(595,842,number).create());y=48f }
        try {
            start()
            for(paragraph in ReportContent.paragraphs(report)) {
                paint.textSize=if(paragraph.title) 14f else 11f
                paint.typeface=Typeface.create("sans-serif",if(paragraph.title)Typeface.BOLD else Typeface.NORMAL)
                val layout=StaticLayout.Builder.obtain(paragraph.text,0,paragraph.text.length,paint,515)
                    .setAlignment(Layout.Alignment.ALIGN_NORMAL).setLineSpacing(3f,1f).setIncludePad(true).build()
                for(line in 0 until layout.lineCount) {
                    val top=layout.getLineTop(line)
                    val bottom=layout.getLineBottom(line)
                    val height=(bottom-top).toFloat()
                    if(y+height>790) {finish();start()}
                    val canvas=requireNotNull(page).canvas
                    canvas.save();canvas.clipRect(40f,y,555f,y+height)
                    canvas.translate(40f,y-top);layout.draw(canvas);canvas.restore()
                    y+=height
                }
                y+=if(paragraph.title)12f else 9f
            }
            finish();page=null
            target.parentFile?.mkdirs()
            val partial=File(target.path+".partial")
            try {partial.outputStream().use {document.writeTo(it)};check(partial.renameTo(target))}
            finally {partial.delete()}
        } finally {document.close()}
    }
}
