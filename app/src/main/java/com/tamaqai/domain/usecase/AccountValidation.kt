package com.tamaqai.domain.usecase

object AccountValidation {
    fun email(value:String):Boolean {
        val email=value.trim()
        // Pydantic EmailStr rejects these too. Invisible copied characters must
        // produce a field error, rather than passing the old permissive regex.
        if(email.any {it.isWhitespace() || Character.isSpaceChar(it) || Character.isISOControl(it) || Character.getType(it)==Character.FORMAT.toInt()}) return false
        val parts=email.split('@')
        if(parts.size!=2 || email.length>254 || parts[0].length !in 1..64) return false
        val local=parts[0]
        if(local.startsWith('.') || local.endsWith('.') || ".." in local || local.any {it in "\"(),:;<>[\\]"}) return false
        val domain=runCatching {java.net.IDN.toASCII(parts[1],java.net.IDN.USE_STD3_ASCII_RULES)}.getOrNull() ?: return false
        val labels=domain.split('.')
        return labels.size>=2 && labels.all {it.length in 1..63 && !it.startsWith('-') && !it.endsWith('-')}
    }
    fun passwordLength(value:String)=value.codePointCount(0,value.length)
    fun registrationPassword(value:String)=passwordLength(value) in 15..128
    fun name(value:String)=value.trim().codePointCount(0,value.trim().length) in 1..80
}
