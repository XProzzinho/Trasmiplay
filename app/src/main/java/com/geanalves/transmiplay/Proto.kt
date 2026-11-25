package com.geanalves.transmiplay

/** Portas e constantes combinadas com o Simulplay (TV). */
object Proto {
    const val DISCOVERY_PORT = 47800   // UDP: o celular avisa "estou aqui" no Wi-Fi
    const val CONTROL_PORT = 47801     // TCP: comandos (tocar, pausar, relógio...)
    const val HTTP_PORT = 47802        // HTTP: serve as músicas do celular
    const val AUDIO_PORT = 47803       // TCP: som ao vivo do modo caixinha
    const val SAMPLE_RATE = 48000
    const val CHUNK_BYTES = 3840       // 20 ms de áudio estéreo 16 bits a 48 kHz
    const val AUDIO_DELAY_MS = 300     // atraso fixo do modo caixinha (pra todas as TVs juntas)
    const val START_LEAD_MS = 600L     // quanto tempo antes as TVs recebem o "começa agora"
}
