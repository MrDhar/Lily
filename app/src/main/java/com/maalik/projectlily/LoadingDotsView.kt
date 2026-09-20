package com.maalik.projectlily

import android.animation.ValueAnimator
import android.content.*
import android.database.Cursor
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.drawable.ColorDrawable
import android.os.Handler
import android.text.*
import android.view.*
import android.widget.*
import android.view.animation.LinearInterpolator
import kotlin.math.min

internal class LoadingDotsView(context:Context):View(context){
        private val colors=intArrayOf(Color.rgb(190,166,218),Color.rgb(232,157,207),Color.rgb(173,155,220))
        private val paint=Paint(Paint.ANTI_ALIAS_FLAG)
        private var animator:ValueAnimator?=null
        private var phase=0f
        private var complete=false
        init{setLayerType(View.LAYER_TYPE_SOFTWARE,null)}
        override fun onDraw(canvas:Canvas){
            super.onDraw(canvas)
            // Keep every animated circle inside the view bounds; the old fixed 54dp
            // canvas could visually clip a dot at the edge on some densities.
            val centerY=height/2f;val gap=dpLocal(18);val base=dpLocal(5);val side=base*1.28f
            val cx=width/2f
            canvas.save()
            canvas.clipRect(0f,0f,width.toFloat(),height.toFloat())
            for(i in 0..2){
                val distance=(phase-i*0.333f).let{var x=it%1f;if(x<0)x+=1f;x}
                val pulse=if(complete)0f else (1f-kotlin.math.abs(distance-0.5f)*2f).coerceIn(0f,1f)
                val radius=base*(0.72f+0.48f*pulse)
                paint.color=colors[i]
                paint.alpha=(105+150*pulse).toInt().coerceIn(0,255)
                val x=(cx+(i-1)*gap).coerceIn(side,width-side)
                canvas.drawCircle(x,centerY,radius.coerceAtMost(side),paint)
            }
            canvas.restore()
        }
        private fun dpLocal(v:Int)=v*resources.displayMetrics.density
        override fun onDetachedFromWindow(){animator?.cancel();animator=null;super.onDetachedFromWindow()}
        fun start(){
            complete=false;animator?.cancel();animator=ValueAnimator.ofFloat(0f,1f).apply{duration=1150;interpolator=LinearInterpolator();repeatCount=ValueAnimator.INFINITE;addUpdateListener{phase=it.animatedValue as Float;invalidate()};start()}
        }
        fun stop(){animator?.cancel();animator=null;complete=false;invalidate()}
        fun showComplete(){animator?.cancel();animator=null;complete=true;phase=.5f;invalidate()}
        fun showError(){animator?.cancel();animator=null;complete=false;phase=.5f;paint.alpha=210;invalidate()}
    }
