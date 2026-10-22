package com.tamaqai.ui

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import java.io.ByteArrayOutputStream

/** Native, synthetic plate fixture. No private user photo is needed for reproducible UI QA. */
internal fun visualFoodFixture(): ByteArray {
    val bitmap=Bitmap.createBitmap(600,480,Bitmap.Config.ARGB_8888)
    val canvas=Canvas(bitmap)
    val paint=Paint(Paint.ANTI_ALIAS_FLAG)
    canvas.drawColor(Color.rgb(222,210,195))
    paint.color=Color.rgb(255,252,247);canvas.drawCircle(300f,240f,198f,paint)
    paint.color=Color.rgb(238,227,212);canvas.drawCircle(300f,240f,165f,paint)
    paint.color=Color.rgb(167,125,83);canvas.drawRoundRect(205f,145f,350f,280f,30f,30f,paint)
    paint.color=Color.rgb(206,167,100)
    repeat(9) {canvas.drawRoundRect(300f+it*5,215f+it*8,408f+it*5,220f+it*8,3f,3f,paint)}
    paint.color=Color.rgb(101,124,76)
    repeat(6) {canvas.drawCircle(206f+it*15,300f+(it%2)*20,16f,paint)}
    return ByteArrayOutputStream().use {out->bitmap.compress(Bitmap.CompressFormat.JPEG,90,out);bitmap.recycle();out.toByteArray()}
}
