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

package com.maltaisn.swfconvert.convert.image

import com.flagstone.transform.image.*
import com.maltaisn.swfconvert.convert.ConvertConfiguration
import com.maltaisn.swfconvert.convert.conversionError
import com.maltaisn.swfconvert.convert.wrapper.WDefineImage
import com.maltaisn.swfconvert.convert.zlibDecompress
import com.maltaisn.swfconvert.core.Disposable
import com.maltaisn.swfconvert.core.image.Color
import com.maltaisn.swfconvert.core.image.ImageData
import com.maltaisn.swfconvert.core.image.ImageDataCreator
import com.maltaisn.swfconvert.core.image.ImageFormat
import com.mortennobel.imagescaling.ResampleOp
import java.awt.image.BufferedImage
import java.io.ByteArrayInputStream
import javax.imageio.ImageIO
import javax.inject.Inject
import kotlin.math.roundToInt


/**
 * Converts SWF image tags to binary image data, optionally applying a color transform.
 * Doesn't support [DefineJPEGImage4] for now. (deblocking filter)
 * See [https://www.adobe.com/content/dam/acom/en/devnet/pdf/swf-file-format-spec.pdf].
 */
class ImageDecoder @Inject constructor(
        private val config: ConvertConfiguration,
        private val imageDataCreator: ImageDataCreator
) : Disposable {

    override fun dispose() {
        imageDataCreator.dispose()
    }

    fun convertImage(image: ImageTag, colorTransform: CompositeColorTransform,
                     density: Float) = when (image) {
        is DefineImage -> convertDefineImage(WDefineImage(image), colorTransform, density)
        is DefineImage2 -> convertDefineImage2(WDefineImage(image), colorTransform, density)
        is DefineJPEGImage2 -> convertJpegImage2(image, colorTransform, density)
        is DefineJPEGImage3 -> convertJpegImage3(image, colorTransform, density)
        else -> {
            conversionError("Unsupported image type")
        }
    }

    // DEFINE BITS LOSSLESS DECODING

    /**
     * DefineBitsLossless is either:
     * - An indexed bitmap image with 256 24-bit colors table. (RGB)
     * - RGB555 (16 bits) encoded bitmap.
     * - RGB888 (24 bits) encoded bitmap.
     */
    private fun convertDefineImage(image: WDefineImage, colorTransform: CompositeColorTransform,
                                   density: Float): ImageData {
        // Create buffered image. RGB channels only, no alpha.
        val buffImage = when (image.bits) {
            8 -> convertIndexedImage(image, BufferedImage.TYPE_INT_RGB, 3, (Color)::fromRgbBytes)
            16 -> convertRawImage(image, BufferedImage.TYPE_INT_RGB, 2, (Color)::fromPix15Bytes)
            24 -> convertRawImage(image, BufferedImage.TYPE_INT_RGB, 4, (Color)::fromPix24Bytes)
            else -> conversionError("Invalid number of image bits")
        }
        return createTransformedImageData(buffImage, colorTransform, density, ImageFormat.PNG)
    }

    /**
     * DefineBitsLossless2 is either:
     * - An indexed bitmap image with 256 32-bit colors table. (RGBA)
     * - ARGB8888 (32 bits) encoded bitmap.
     */
    private fun convertDefineImage2(image: WDefineImage, colorTransform: CompositeColorTransform,
                                    density: Float): ImageData {
        // Create buffered image. RGB channels + alpha channel.
        val buffImage = when (image.bits) {
            8 -> convertIndexedImage(image, BufferedImage.TYPE_INT_ARGB, 4, (Color)::fromRgbaBytes)
            32 -> convertRawImage(image, BufferedImage.TYPE_INT_ARGB, 4, (Color)::fromArgbBytes)
            else -> conversionError("Invalid number of image bits")
        }
        return createTransformedImageData(buffImage, colorTransform, density, ImageFormat.PNG)
    }

    /**
     * Converts a DefineBitsLossless image tag that uses a color table
     * to a [BufferedImage]. Colors in table occupy a certain number of [bytes]
     * and are decoded to ARGB values using [bitsConverter].
     */
    private fun convertIndexedImage(image: WDefineImage, type: Int, bytes: Int,
                                    bitsConverter: (ByteArray, Int) -> Color): BufferedImage {
        // Image data is color table then pixel data as indices in color table.
        // Color table colors is either RGB or RGBA.
        val colors = IntArray(image.tableSize) {
            bitsConverter(image.data, it * bytes).value
        }
        val buffImage = BufferedImage(image.width, image.height, type)
        var pos = image.tableSize * bytes
        var i = 0
        for (y in 0 until image.height) {
            for (x in 0 until image.width) {
                buffImage.setRGB(x, y, colors[image.data[pos].toInt() and 0xFF])
                pos++
                i++
            }
            while (i % 4 != 0) {
                // Pad to 32-bits (relative to the start of the pixel data!)
                pos++
                i++
            }
        }

        return buffImage
    }

    /**
     * Converts a DefineBitsLossless image tag encoding a bitmap to
     * a [BufferedImage]. Colors in the bitmap occupy a certain number of [bytes],
     * and are decoded to ARGB values using [bitsConverter]
     */
    private fun convertRawImage(image: WDefineImage, type: Int, bytes: Int,
                                bitsConverter: (ByteArray, Int) -> Color): BufferedImage {
        // Image data only. Data can be PIX15, PIX24 or ARGB.
        val buffImage = BufferedImage(image.width, image.height, type)
        var i = 0
        for (y in 0 until image.height) {
            for (x in 0 until image.width) {
                buffImage.setRGB(x, y, bitsConverter(image.data, i).value)
                i += bytes
            }
            while (i % 4 != 0) {
                i++  // Pad to 32-bits
            }
        }
        return buffImage
    }


    // DEFINE JPEG DECODING

    /**
     * DefineBitsJPEG2 tag is just plain JPEG data, without alpha channel.
     */
    private fun convertJpegImage2(image: DefineJPEGImage2, colorTransform: CompositeColorTransform,
                                  density: Float): ImageData {
        val buffImage = ImageIO.read(ByteArrayInputStream(image.image))
        return createTransformedImageData(buffImage, colorTransform, density, ImageFormat.JPG)
    }

    /**
     * DefineBitsJPEG3 tag is JPEG data with a ZLIB compressed alpha channel.
     */
    private fun convertJpegImage3(image: DefineJPEGImage3, colorTransform: CompositeColorTransform,
                                  density: Float): ImageData {
        // JPEG/PNG/GIF image where alpha channel is stored separatedly.
        val w = image.width
        val h = image.height
        val buffImage = ImageIO.read(ByteArrayInputStream(image.image))

        // Read alpha channel which is compressed with ZLIB.
        // Each byte is the alpha value for a pixel, the array being (width * height) long.
        val alphaBytes = image.alpha.zlibDecompress()
        if (alphaBytes.isEmpty()) {
            // For PNG and GIF data, no alpha can be specified, use image as-is.
            return createTransformedImageData(buffImage, colorTransform, density, ImageFormat.JPG)
        }
        assert(alphaBytes.size == w * h)

        // Create new image and copy each pixel, setting alpha value
        // Also premultiply alpha values.
        val newImage = BufferedImage(w, h, BufferedImage.TYPE_INT_ARGB)
        var i = 0
        for (y in 0 until h) {
            for (x in 0 until w) {
                var color = Color(buffImage.getRGB(x, y))  // Get pixel color without alpha
                color = color.withAlpha(alphaBytes[i].toInt() and 0xFF)  // Set alpha value on color
                color = color.divideAlpha()
                newImage.setRGB(x, y, color.value)
                i++
            }
        }

        return createTransformedImageData(newImage, colorTransform, density, ImageFormat.JPG)
    }

    // BUFFERED IMAGE UTILS

    /**
     * Create image data for an [buffImage]. Created data uses [ConvertConfiguration.imageFormat],
     * or [defaultFormat] if no format is forced. Also applies color transform, vertical flip,
     * and downsampling to [buffImage].
     *
     * @param density Density of [buffImage] in DPI. Use `null` to disable downsampling.
     * @param colorTransform Color transform to apply on [buffImage]. Use `null` for none.
     */
    private fun createTransformedImageData(buffImage: BufferedImage,
                                           colorTransform: CompositeColorTransform?,
                                           density: Float?,
                                           defaultFormat: ImageFormat): ImageData {
        // Transform image
        var image = buffImage
        colorTransform?.transform(image)
        if (density != null) {
            image = image.downsampled(density, config.maxDpi)
        }

        // Create image data
        val format = config.imageFormat ?: defaultFormat
        return imageDataCreator.createImageData(image, format, config.jpegQuality)
    }

    /**
     * If [ConvertConfiguration.downsampleImages] is `true`, this will downsample [this] image
     * if its [currentDensity] is over [maxDensity]. New density will be [maxDensity].
     * If density is already below or downsampling is disabled, the same image is returned.
     */
    private fun BufferedImage.downsampled(currentDensity: Float,
                                          maxDensity: Float): BufferedImage {
        val min = config.downsampleMinSize.toFloat()
        if (!config.downsampleImages || currentDensity < maxDensity ||
                this.width < min || this.height < min) {
            // Downsampling disabled, or density is below maximum, or size is already very small.
            return this
        }

        val scale = maxDensity / currentDensity
        var w = this.width * scale
        var h = this.height * scale

        // Make sure we're not downsampling to below minimum size.
        if (w < min) {
            h *= min / w
            w = min
        }
        if (h < min) {
            w *= min / h
            h = min
        }

        val iw = w.roundToInt()
        val ih = h.roundToInt()
        val resizeOp = ResampleOp(iw, ih)
        resizeOp.filter = config.downsampleFilter!!
        // TODO implement "fast" filter
        val destImage = BufferedImage(iw, ih, this.type)
        return resizeOp.filter(this, destImage)
    }

}