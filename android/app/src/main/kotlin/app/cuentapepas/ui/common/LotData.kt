package app.cuentapepas.ui.common

import app.cuentapepas.core.session.Lot
import app.cuentapepas.core.session.ManualCount
import app.cuentapepas.core.session.PlantRecord
import app.cuentapepas.core.session.SessionMeta
import app.cuentapepas.core.session.SessionStatus
import app.cuentapepas.core.store.LotNotFoundException
import app.cuentapepas.data.SessionStore

/** Everything the calibration and estimate screens need about one lot. Plants are loaded for COMPLETED sessions only. */
data class LotData(
    val lot: Lot,
    val sessions: List<SessionMeta>,
    val plants: List<PlantRecord>,
    val manualCounts: List<ManualCount>,
)

suspend fun SessionStore.loadLotData(lot: Lot): LotData {
    val sessions = sessions(lot.id)
    val plants = sessions.filter { it.status == SessionStatus.COMPLETED }.flatMap { plants(it.id) }
    return LotData(lot, sessions, plants, manualCounts(lot.id))
}

/** @throws LotNotFoundException when the lot does not exist. */
suspend fun SessionStore.loadLotData(lotId: String): LotData {
    val lot = lots().firstOrNull { it.id == lotId } ?: throw LotNotFoundException(lotId)
    return loadLotData(lot)
}
