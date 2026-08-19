package com.msp1974.vacompanion.gideon

import android.webkit.JavascriptInterface

/**
 * `window.GideonVoice` — the dashboard's window onto the live audio meter.
 *
 * Registered in CustomWebView.initialise() alongside the app's existing three
 * JS interfaces. Deliberately tiny: every method is a synchronous read, there is
 * no callback into JS, and nothing here can affect the satellite. Removing the
 * one registration line removes the whole feature from the app's behaviour.
 *
 * Pull model, not push: the page drains on its own animation frame, so a page
 * that is hidden, navigating, or asleep simply stops asking and the meter's ring
 * quietly overwrites itself. Pushing via evaluateJavascript from an audio thread
 * would put WebView work on the critical path of the voice pipeline, which is
 * exactly what this feature must never do.
 *
 * Wire format is documented on GideonVoiceMeter; gideon-voice.js is the contract
 * the dashboard actually codes against.
 */
object GideonVoiceBridge {

    /** Everything heard or spoken since the last call. `{v:[],p:[],s:int,hz:int}` */
    @JavascriptInterface
    fun drain(): String = GideonVoiceMeter.drain()

    /** Static description of the stream: protocol version, rate, slice length. */
    @JavascriptInterface
    fun info(): String = GideonVoiceMeter.info()
}
