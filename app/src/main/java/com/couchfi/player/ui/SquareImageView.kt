package com.couchfi.player.ui

import android.content.Context
import android.util.AttributeSet
import android.widget.ImageView

/** ImageView that forces its height equal to its measured width, giving
 *  tile art a reliable 1:1 aspect regardless of the source bitmap. */
class SquareImageView @JvmOverloads constructor(
    context: Context, attrs: AttributeSet? = null, defStyle: Int = 0,
) : ImageView(context, attrs, defStyle) {
    override fun onMeasure(widthSpec: Int, heightSpec: Int) {
        super.onMeasure(widthSpec, widthSpec)
    }
}
