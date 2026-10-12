package app.cuentapepas.core.store

import java.io.IOException

/**
 * A persisted file exists but cannot be decoded (bad JSON, wrong shape, invalid values). The store never repairs,
 * recreates or skips such a file; the UI shows [path] so the user can export or remove it deliberately.
 */
class CorruptDataException(val path: String, detail: String, cause: Throwable? = null) :
    IOException("Datos corruptos en $path: $detail", cause)

class SessionNotFoundException(val sessionId: String) : NoSuchElementException("Sesión no encontrada: $sessionId")

class LotNotFoundException(val lotId: String) : NoSuchElementException("Lote no encontrado: $lotId")

/** A lot cannot be deleted while sessions (of any status) still belong to it. */
class LotHasSessionsException(val lotId: String, val sessionCount: Int) :
    IllegalStateException("El lote $lotId tiene $sessionCount sesión(es) y no se puede borrar")
