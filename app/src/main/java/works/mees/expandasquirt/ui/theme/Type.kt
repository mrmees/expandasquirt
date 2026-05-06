package works.mees.expandasquirt.ui.theme

import androidx.compose.material3.Typography
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily

private val BaseTypography = Typography()

val ExpandasquirtTypography = Typography(
    displayLarge = BaseTypography.displayLarge.toMonospace(),
    displayMedium = BaseTypography.displayMedium.toMonospace(),
    headlineLarge = BaseTypography.headlineLarge.toMonospace(),
    headlineMedium = BaseTypography.headlineMedium.toMonospace(),
    headlineSmall = BaseTypography.headlineSmall,
    titleLarge = BaseTypography.titleLarge,
    titleMedium = BaseTypography.titleMedium,
    titleSmall = BaseTypography.titleSmall,
    bodyLarge = BaseTypography.bodyLarge,
    bodyMedium = BaseTypography.bodyMedium,
    bodySmall = BaseTypography.bodySmall,
    labelLarge = BaseTypography.labelLarge,
    labelMedium = BaseTypography.labelMedium,
    labelSmall = BaseTypography.labelSmall,
)

private fun TextStyle.toMonospace(): TextStyle = copy(fontFamily = FontFamily.Monospace)
