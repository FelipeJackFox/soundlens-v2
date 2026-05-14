package com.example.soundlens.uiutils

import android.animation.Animator
import android.animation.AnimatorListenerAdapter
import android.animation.AnimatorSet
import android.animation.ObjectAnimator
import android.view.View
import android.view.animation.AccelerateDecelerateInterpolator

object AnimUtils {

    private var pulseAnimator: AnimatorSet? = null

    fun startPulse(mainCircle: View, innerIcon: View) {
        val upCircleX = ObjectAnimator.ofFloat(mainCircle, "scaleX", 1f, 1.08f)
        val upCircleY = ObjectAnimator.ofFloat(mainCircle, "scaleY", 1f, 1.08f)
        val upLogoX   = ObjectAnimator.ofFloat(innerIcon, "scaleX", 1f, 1.05f)
        val upLogoY   = ObjectAnimator.ofFloat(innerIcon, "scaleY", 1f, 1.05f)

        val downCircleX = ObjectAnimator.ofFloat(mainCircle, "scaleX", 1.08f, 1f)
        val downCircleY = ObjectAnimator.ofFloat(mainCircle, "scaleY", 1.08f, 1f)
        val downLogoX   = ObjectAnimator.ofFloat(innerIcon, "scaleX", 1.05f, 1f)
        val downLogoY   = ObjectAnimator.ofFloat(innerIcon, "scaleY", 1.05f, 1f)

        val up = AnimatorSet().apply {
            playTogether(upCircleX, upCircleY, upLogoX, upLogoY)
            duration = 500
            interpolator = AccelerateDecelerateInterpolator()
        }
        val down = AnimatorSet().apply {
            playTogether(downCircleX, downCircleY, downLogoX, downLogoY)
            duration = 500
            interpolator = AccelerateDecelerateInterpolator()
        }

        pulseAnimator?.cancel()
        pulseAnimator = AnimatorSet().apply {
            playSequentially(up, down)
            addListener(object : AnimatorListenerAdapter() {
                override fun onAnimationEnd(animation: Animator) {
                    if (this@apply == pulseAnimator) start()
                }
            })
            start()
        }
    }

    fun stopPulse(mainCircle: View, innerIcon: View) {
        pulseAnimator?.cancel()
        pulseAnimator = null
        mainCircle.scaleX = 1f
        mainCircle.scaleY = 1f
        innerIcon.scaleX = 1f
        innerIcon.scaleY = 1f
    }
}
