package com.vyron.os.services

import android.service.voice.VoiceInteractionSessionService
import android.service.voice.VoiceInteractionSession
import android.os.Bundle

class VyronVoiceInteractionSessionService : VoiceInteractionSessionService() {
    override fun onNewSession(args: Bundle?): VoiceInteractionSession {
        return VyronVoiceInteractionSession(this)
    }
}
