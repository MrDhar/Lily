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

class LineNumberEditText(context:Context, attrs:android.util.AttributeSet?=null):android.widget.EditText(context,attrs){
    private var lastContextX=0f
    private var lastContextY=0f
    private val zoomDetector=android.view.ScaleGestureDetector(context,object:android.view.ScaleGestureDetector.SimpleOnScaleGestureListener(){
        override fun onScale(detector:android.view.ScaleGestureDetector):Boolean{
            (context as? MainActivity)?.applyEditorZoomFromGesture(detector.scaleFactor)
            return true
        }
    })

    override fun dispatchTouchEvent(event:android.view.MotionEvent):Boolean{
        // Observe touch events for pinch-zoom without becoming the editor's
        // touch listener. The native EditText pipeline always receives the
        // same event afterwards, so taps, cursor placement, selection, drag
        // and scrolling remain standard Android behaviour.
        zoomDetector.onTouchEvent(event)
        return super.dispatchTouchEvent(event)
    }

    /** Called by Lily only for the physical mouse secondary-click menu. */
    fun performLilyTextAction(id:Int):Boolean = super.onTextContextMenuItem(id)

    override fun performContextClick():Boolean{
        val activity=context as? MainActivity
        if(activity!=null){
            activity.showEditorContextMenu(this,lastContextX,lastContextY)
            return true
        }
        return super.performContextClick()
    }

    override fun onTouchEvent(event:android.view.MotionEvent):Boolean{
        // Intercept only a real mouse secondary click. Every other event stays on
        // the native EditText path so cursor placement, typing, selection,
        // scrolling, touch and primary-mouse drag behaviour remain standard.
        if(event.source and android.view.InputDevice.SOURCE_MOUSE == android.view.InputDevice.SOURCE_MOUSE){
            if(event.actionMasked==android.view.MotionEvent.ACTION_DOWN &&
                (event.buttonState and android.view.MotionEvent.BUTTON_SECONDARY)!=0){
                lastContextX=event.x
                lastContextY=event.y
                (context as? MainActivity)?.showEditorContextMenu(this,event.x,event.y)
                return true
            }
            if((event.buttonState and android.view.MotionEvent.BUTTON_SECONDARY)!=0 &&
                (event.actionMasked==android.view.MotionEvent.ACTION_UP ||
                 event.actionMasked==android.view.MotionEvent.ACTION_CANCEL)){
                return true
            }
        }
        if(event.actionMasked==android.view.MotionEvent.ACTION_HOVER_MOVE ||
           event.actionMasked==android.view.MotionEvent.ACTION_HOVER_ENTER){
            lastContextX=event.x
            lastContextY=event.y
        }
        return super.onTouchEvent(event)
    }
    override fun onSelectionChanged(selStart:Int,selEnd:Int){
        super.onSelectionChanged(selStart,selEnd)
        // Selection changes are also autocomplete triggers. Do not immediately
        // dismiss here: update() performs the schema lookup asynchronously, so
        // the popup is not visible yet when this callback runs. Dismissing at
        // this point invalidates the in-flight completion request and makes
        // autocomplete appear permanently dead. The autocomplete controller
        // owns its own visibility/lifecycle.
        (context as? MainActivity)?.onEditorSelectionChanged()
    }
    private val paint=android.graphics.Paint().apply{color=Color.rgb(91,95,108);textSize=36f;typeface=android.graphics.Typeface.MONOSPACE}
    init{
        // SQL editors should not impose a practical line limit. Keep the text vertically
        // scrollable while preserving a fixed-width monospace editor (no surprise wrapping).
        setMaxLines(Int.MAX_VALUE)
        setHorizontallyScrolling(true)
        // Android must not inject its own smart-text suggestions/actions into the SQL editor.
        inputType = android.text.InputType.TYPE_CLASS_TEXT or
            android.text.InputType.TYPE_TEXT_FLAG_MULTI_LINE or
            android.text.InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
        if(android.os.Build.VERSION.SDK_INT>=26){
            try{setTextClassifier(android.view.textclassifier.TextClassifier.NO_OP)}catch(_:Throwable){}
        }
        setVerticalScrollBarEnabled(true)
        setHorizontalScrollBarEnabled(true)
        setOverScrollMode(android.view.View.OVER_SCROLL_IF_CONTENT_SCROLLS)
        setScroller(android.widget.Scroller(context))
        isVerticalScrollBarEnabled=true
        isHorizontalScrollBarEnabled=true
        // The editor is a fixed viewport, not a fixed number of SQL lines.
        // Explicit scrolling keeps long scripts from appearing to stop at the
        // bottom of the visible viewport.
        setPadding((52*resources.displayMetrics.density).toInt(),paddingTop,paddingRight,paddingBottom)
        setBackgroundColor(Color.TRANSPARENT)
        setTextColor(Color.rgb(231,227,233))
        if(android.os.Build.VERSION.SDK_INT>=29){
            try{ textCursorDrawable?.setTint(Color.rgb(231,227,233)) }catch(_:Throwable){}
        }
        isEnabled=true
        isFocusable=true
        isFocusableInTouchMode=true
        isClickable=true
        isLongClickable=true
        showSoftInputOnFocus=true
        setOnFocusChangeListener{_,_->invalidate()}
        setOnScrollChangeListener{_,_,_,_,_->invalidate()}
    }
    override fun onDraw(canvas:android.graphics.Canvas){
        // Gutter is a sibling view. The editor itself paints only SQL text,
        // so no gutter rectangle/current-line patch can be introduced here.
        super.onDraw(canvas)
    }
}


class LineNumberGutterView(context:Context,attrs:android.util.AttributeSet?=null):android.view.View(context,attrs){
    private var editor:LineNumberEditText?=null
    private val paint=android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply{
        color=Color.rgb(91,95,108)
        typeface=android.graphics.Typeface.MONOSPACE
        textAlign=android.graphics.Paint.Align.RIGHT
    }
    private val dividerPaint=android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply{
        color=Color.rgb(34,36,41)
        strokeWidth=1f
    }

    fun bindEditor(value:LineNumberEditText){
        editor=value
        value.viewTreeObserver.addOnScrollChangedListener{invalidate()}
        value.viewTreeObserver.addOnGlobalLayoutListener{invalidate()}
        value.addTextChangedListener(object:TextWatcher{
            override fun beforeTextChanged(s:CharSequence?,st:Int,c:Int,a:Int){}
            override fun onTextChanged(s:CharSequence?,st:Int,b:Int,c:Int){postInvalidateOnAnimation()}
            override fun afterTextChanged(e:Editable?){}
        })
        value.addOnLayoutChangeListener{_,_,_,_,_,_,_,_,_->postInvalidateOnAnimation()}
        postInvalidateOnAnimation()
    }

    override fun onDraw(canvas:android.graphics.Canvas){
        super.onDraw(canvas)
        val e=editor ?: return
        val layout=e.layout ?: return
        if(layout.lineCount<=0)return

        val d=resources.displayMetrics.density
        paint.textSize=e.textSize*0.78f

        // Use the editor's actual scroll position and Layout baselines.
        // This is independent of the editor's horizontal scroll and therefore
        // cannot be clipped/shifted when the SQL is horizontally scrolled.
        val scrollY=e.scrollY
        val first=layout.getLineForVertical((scrollY-e.paddingTop).coerceAtLeast(0))
            .coerceIn(0,layout.lineCount-1)
        val last=layout.getLineForVertical(
            (scrollY+height-e.paddingTop).coerceAtLeast(0)
        ).coerceIn(first,layout.lineCount-1)

        for(line in first..last){
            val y=e.paddingTop + layout.getLineBaseline(line) - scrollY
            if(y>=-paint.textSize && y<=height+paint.textSize){
                canvas.drawText((line+1).toString(),width-8*d,y.toFloat(),paint)
            }
        }
        canvas.drawLine(width-1f,0f,width-1f,height.toFloat(),dividerPaint)
    }

    override fun onTouchEvent(event:android.view.MotionEvent):Boolean = false

    init{
        isClickable=false
        isFocusable=false
        isFocusableInTouchMode=false
        setWillNotDraw(false)
        setBackgroundColor(Color.TRANSPARENT)
    }
}
