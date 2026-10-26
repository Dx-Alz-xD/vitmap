package com.vitmap.app.nav

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import com.vitmap.app.feature.browser.BrowserScreen
import com.vitmap.app.feature.chess.ChessScreen
import com.vitmap.app.feature.clock.ClockScreen
import com.vitmap.app.feature.color.ColorPickerScreen
import com.vitmap.app.feature.compress.CompressScreen
import com.vitmap.app.feature.convert.ConvertScreen
import com.vitmap.app.feature.fileconvert.FileConvertScreen
import com.vitmap.app.feature.forex.ForexScreen
import com.vitmap.app.feature.scan.PdfToolsScreen
import com.vitmap.app.feature.scan.ScannerScreen
import com.vitmap.app.feature.measure.MeasureScreen
import com.vitmap.app.feature.sudoku.SudokuScreen
import com.vitmap.app.feature.tictactoe.TicTacToeScreen
import com.vitmap.app.feature.translate.TranslatorScreen

@Composable
fun ToolHost(route: ToolRoute, onBack: () -> Unit, onOpenTool: (String, String?) -> Unit) {
    when (route.id) {
        "chess" -> ChessScreen(onBack)
        "tictactoe" -> TicTacToeScreen(onBack)
        "color" -> ColorPickerScreen(onBack)
        "measure" -> MeasureScreen(onBack)
        "translate" -> TranslatorScreen(onBack, initialText = route.arg)
        "browser" -> BrowserScreen(onBack, initialUrl = route.arg)
        "convert" -> ConvertScreen(onBack)
        "scanner" -> ScannerScreen(onBack)
        "pdftools" -> PdfToolsScreen(onBack, onOpenTool)
        "sudoku" -> SudokuScreen(onBack)
        "clock" -> ClockScreen(onBack)
        "forex" -> ForexScreen(onBack)
        "compress" -> CompressScreen(onBack, initialUri = route.arg)
        "fileconvert" -> FileConvertScreen(onBack, initialUri = route.arg)
        else -> LaunchedEffect(route.id) { onBack() }
    }
}
