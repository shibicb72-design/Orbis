package com.shibi.orbis.passwords

import com.shibi.orbis.engine.EngineLogin
import com.shibi.orbis.engine.LoginBridge

/** The engine's view of [LoginManager]; [offerSaving] is the "Offer to save passwords" setting, read when asked. */
class LoginBridgeImpl(private val manager: LoginManager, private val offerSaving: () -> Boolean) : LoginBridge {
    override fun fetch(domain: String): List<EngineLogin> =
        manager.forDomain(domain).map { EngineLogin(it.guid, it.origin, it.formActionOrigin, it.httpRealm, it.username, it.password) }

    override fun offerSave(origin: String, isPrivate: Boolean): Boolean =
        !isPrivate && offerSaving() && manager.loaded && !manager.isNeverSave(origin)

    override fun isUpdate(origin: String, username: String, password: String): Boolean = manager.isUpdate(origin, username, password)

    override fun save(login: EngineLogin) {
        manager.save(login.origin, login.username, login.password, login.formActionOrigin, login.httpRealm, login.guid)
    }

    override fun used(guid: String) = manager.markUsed(guid)

    override fun neverFor(origin: String) = manager.setNeverSave(origin, true)
}
