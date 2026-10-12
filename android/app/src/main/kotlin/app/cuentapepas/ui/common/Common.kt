package app.cuentapepas.ui.common

import android.content.Context
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.State
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import app.cuentapepas.CuentapepasApp
import app.cuentapepas.R
import app.cuentapepas.ServiceLocator
import app.cuentapepas.core.session.DataIntegrityException
import app.cuentapepas.core.store.CorruptDataException
import app.cuentapepas.core.store.LotNotFoundException
import app.cuentapepas.inference.ModelException
import app.cuentapepas.inference.ModelInvalidException
import app.cuentapepas.inference.ModelLoadException
import app.cuentapepas.inference.ModelMissingException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

val Context.services: ServiceLocator get() = (applicationContext as CuentapepasApp).services

/** Spanish, user-facing description of a failure. Typed exceptions get specific messages; nothing is hidden. */
fun Context.describe(e: Throwable): String = when (e) {
    is ModelMissingException -> getString(R.string.model_missing, e.assetPath)
    is ModelInvalidException -> getString(R.string.model_invalid, e.message.orEmpty())
    is ModelLoadException -> getString(R.string.model_load_failed, e.message.orEmpty())
    is CorruptDataException -> getString(R.string.data_corrupt, e.path)
    is DataIntegrityException -> getString(R.string.error_integrity, e.message.orEmpty())
    is LotNotFoundException -> getString(R.string.error_lot_missing)
    else -> getString(R.string.error_generic, e.message ?: e::class.java.simpleName)
}

sealed interface Load<out T> {
    data object Loading : Load<Nothing>
    data class Ok<T>(val value: T) : Load<T>
    data class Failed(val message: String) : Load<Nothing>
}

class Loader<T>(val state: State<Load<T>>, val reload: () -> Unit)

/** Runs [block] off the main thread whenever [key] changes or [Loader.reload] is called; failures become [Load.Failed]. */
@Composable
fun <T> rememberLoader(key: Any?, block: suspend () -> T): Loader<T> {
    val context = LocalContext.current
    var tick by remember { mutableIntStateOf(0) }
    val state = produceState<Load<T>>(Load.Loading, key, tick) {
        value = try {
            Load.Ok(withContext(Dispatchers.Default) { block() })
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Load.Failed(context.describe(e))
        }
    }
    return remember(state) { Loader(state) { tick++ } }
}

@Composable
fun <T> ColumnScope.LoadView(loader: Loader<T>, content: @Composable ColumnScope.(T) -> Unit) {
    when (val s = loader.state.value) {
        Load.Loading -> {
            CircularProgressIndicator()
            Text(stringResource(R.string.common_loading))
        }
        is Load.Failed -> {
            ErrorText(s.message)
            Button(onClick = loader.reload) { Text(stringResource(R.string.common_retry)) }
        }
        is Load.Ok -> content(s.value)
    }
}

@Composable
fun ErrorText(message: String) {
    Text(message, color = MaterialTheme.colorScheme.error)
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ScreenScaffold(
    title: String,
    onBack: (() -> Unit)?,
    actions: @Composable RowScope.() -> Unit = {},
    content: @Composable ColumnScope.() -> Unit,
) {
    Scaffold(topBar = {
        TopAppBar(
            title = { Text(title) },
            navigationIcon = {
                if (onBack != null) TextButton(onClick = onBack) { Text(stringResource(R.string.common_back)) }
            },
            actions = actions,
        )
    }) { pad ->
        Column(
            Modifier.fillMaxSize().padding(pad).padding(16.dp).verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(12.dp),
            content = content,
        )
    }
}

/** Display name (Spanish) of a model class id from model.json. Unknown ids are shown as they are. */
fun displayClassName(raw: String): String = when (raw) {
    "green" -> "Verde"
    "semi_ripe" -> "Pintón"
    "ripe" -> "Maduro"
    "overripe" -> "Sobremaduro"
    "dry" -> "Seco"
    else -> raw
}

/** Class names from the model contract, or generic names when the model is unavailable (lots stay viewable). */
fun ServiceLocator.classNames(count: Int): List<String> =
    try {
        modelSpec.classes.map(::displayClassName).takeIf { it.size == count }
            ?: List(count) { "Clase ${it + 1}" }
    } catch (e: ModelException) {
        List(count) { "Clase ${it + 1}" }
    }
