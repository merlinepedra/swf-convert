/*
 * Copyright 2020 Nicolas Maltais
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.maltaisn.swfconvert.render.pdf

import com.maltaisn.swfconvert.core.frame.BlendMode
import com.maltaisn.swfconvert.core.image.data.Color
import org.apache.pdfbox.pdmodel.graphics.state.PDExtendedGraphicsState
import org.apache.pdfbox.util.Matrix
import java.awt.BasicStroke
import java.awt.geom.AffineTransform
import java.util.*
import kotlin.properties.ReadWriteProperty
import kotlin.reflect.KProperty
import org.apache.pdfbox.pdmodel.graphics.blend.BlendMode as PdfBlendMode


/**
 * A wrapper around PDF content [stream], so that changing a property to
 * the current value doesn't output any PDF commands.
 */
internal class PdfStreamWrapper(val stream: PdfContentStream) {

    private val stateStack = LinkedList<State>()
    var restoringState = false


    var blendMode: BlendMode by stateProperty(BlendMode.NORMAL) { new, _ ->
        setExtendedState {
            blendMode = when (new) {
                BlendMode.NORMAL -> PdfBlendMode.NORMAL
                BlendMode.MULTIPLY -> PdfBlendMode.MULTIPLY
                BlendMode.LIGHTEN -> PdfBlendMode.LIGHTEN
                BlendMode.DARKEN -> PdfBlendMode.DARKEN
                BlendMode.HARD_LIGHT -> PdfBlendMode.HARD_LIGHT
                BlendMode.SCREEN -> PdfBlendMode.SCREEN
                BlendMode.OVERLAY -> PdfBlendMode.OVERLAY
            }
        }
    }

    var strokingColor: Color by stateProperty(Color.BLACK) { new, old ->
        stream.setStrokingColor(new)
        if (new.a != old.a && (new.a != 255 || old.a != 255)) {
            setExtendedState {
                strokingAlphaConstant = new.a / 255f
            }
        }
    }

    var nonStrokingColor: Color by stateProperty(Color.BLACK) { new, old ->
        stream.setNonStrokingColor(new)
        if (new.a != old.a && (new.a != 255 || old.a != 255)) {
            setExtendedState {
                nonStrokingAlphaConstant = new.a / 255f
            }
        }
    }

    var lineWidth: Float by stateProperty(0f) { new, _ ->
        stream.setLineWidth(new)
    }

    var lineCapStyle: Int by stateProperty(BasicStroke.CAP_BUTT) { new, _ ->
        stream.setLineCapStyle(new)
    }

    var lineJoinStyle: Int by stateProperty(BasicStroke.JOIN_BEVEL) { new, _ ->
        stream.setLineJoinStyle(new)
    }

    var miterLimit: Float by stateProperty(0f) { new, _ ->
        stream.setMiterLimit(new)
    }


    init {
        stream.setStrokingColor(strokingColor)
        stream.setNonStrokingColor(nonStrokingColor)
        stream.setLineJoinStyle(lineJoinStyle)
        stream.setLineCapStyle(lineCapStyle)
    }


    fun saveState() {
        stateStack.push(State(blendMode, strokingColor, nonStrokingColor,
                lineWidth, lineCapStyle, lineJoinStyle, miterLimit))
        stream.saveGraphicsState()
    }

    fun restoreState() {
        check(stateStack.isNotEmpty()) { "No state to restore" }

        val state = stateStack.pop()

        restoringState = true
        blendMode = state.blendMode
        strokingColor = state.strokingColor
        nonStrokingColor = state.nonStrokingColor
        lineWidth = state.lineWidth
        lineCapStyle = state.lineCapStyle
        lineJoinStyle = state.lineJoinStyle
        miterLimit = state.miterLimit
        restoringState = false

        stream.restoreGraphicsState()
    }

    fun transform(transform: AffineTransform) {
        if (!transform.isIdentity) {
            stream.transform(Matrix(transform))
        }
    }

    inline fun withState(block: () -> Unit) {
        saveState()
        block()
        restoreState()
    }

    inline fun setExtendedState(config: PDExtendedGraphicsState.() -> Unit) {
        val state = PDExtendedGraphicsState()
        state.config()
        stream.setGraphicsStateParameters(state)
    }

    private inline fun <T> stateProperty(value: T, crossinline write: (new: T, old: T) -> Unit)
            : ReadWriteProperty<PdfStreamWrapper, T> =
            object : ReadWriteProperty<PdfStreamWrapper, T> {
                var value = value

                override fun getValue(thisRef: PdfStreamWrapper, property: KProperty<*>) = this.value

                override fun setValue(thisRef: PdfStreamWrapper, property: KProperty<*>, value: T) {
                    if (thisRef.restoringState) {
                        // Restore state value without setting it on PDF stream
                        this.value = value

                    } else if (this.value != value) {
                        write(value, this.value)
                        this.value = value
                    }
                }

                override fun toString() = value.toString()
            }


    data class State(val blendMode: BlendMode,
                     val strokingColor: Color,
                     val nonStrokingColor: Color,
                     val lineWidth: Float,
                     val lineCapStyle: Int,
                     val lineJoinStyle: Int,
                     val miterLimit: Float)

}