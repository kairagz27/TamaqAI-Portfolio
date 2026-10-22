package com.tamaqai.ui.theme

import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.ExperimentalTextApi
import androidx.compose.ui.text.font.*
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.tamaqai.R

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.tamaqai.TamaqApplication
import com.tamaqai.data.local.ThemeMode

data class WellnessPalette(
    val PageBackground:Color,
    val CardSurface:Color,
    val SecondarySurface:Color,
    val AccentBrown:Color,
    val TextPrimary:Color,
    val TextSecondary:Color,
    val BorderSoft:Color,
    val ControlOutline:Color,
    val SelectedSurface:Color,
    val DisabledSurface:Color,
    val ErrorColor:Color,
    val ErrorSurface:Color,
    val WarningColor:Color,
    val SuccessColor:Color,
    val ProteinColor:Color,
    val FatColor:Color,
    val CarbColor:Color,
    val ChartTrack:Color,
    val ShadowColor:Color,
    val GlassEdge:Color,
    val Caramel:Color,
    val Apricot:Color,
    // Legacy API name retained for existing callers; this is warm sand, never green.
    val Pistachio:Color,
    val ProteinPastel:Color
)
// Warm wellness colors restored from 838820e / 080b252; no dynamic color input.
val LightPalette=WellnessPalette(
    PageBackground=Color(0xFFF7F2EA),
    CardSurface=Color(0xFFFFFCF7),
    SecondarySurface=Color(0xFFEEE3D4),
    AccentBrown=Color(0xFF6B4F3A),
    TextPrimary=Color(0xFF332A24),
    TextSecondary=Color(0xFF665A50),
    BorderSoft=Color(0xFFDED2C3),
    ControlOutline=Color(0xFF897460),
    SelectedSurface=Color(0xFFE9D8C6),
    DisabledSurface=Color(0xFFE8DED1),
    ErrorColor=Color(0xFFA13A32),
    ErrorSurface=Color(0xFFFBE5DF),
    WarningColor=Color(0xFF80551F),
    SuccessColor=Color(0xFF476347),
    ProteinColor=Color(0xFF82606B),
    FatColor=Color(0xFF805327),
    CarbColor=Color(0xFF66503D),
    ChartTrack=Color(0xFFDACDBD),
    ShadowColor=Color(0xFF332A24),
    GlassEdge=Color(0xFFFFFFFF),
    Caramel=Color(0xFFAE744C),
    Apricot=Color(0xFFDAB28D),
    Pistachio=Color(0xFFDDC8AE),
    ProteinPastel=Color(0xFFF0DCC8)
)
val DarkPalette=WellnessPalette(
    PageBackground=Color(0xFF211C18),
    CardSurface=Color(0xFF302720),
    SecondarySurface=Color(0xFF3C3026),
    AccentBrown=Color(0xFFE0B590),
    TextPrimary=Color(0xFFF4EBDD),
    TextSecondary=Color(0xFFCABBAB),
    BorderSoft=Color(0xFF514237),
    ControlOutline=Color(0xFFA28C78),
    SelectedSurface=Color(0xFF49372B),
    DisabledSurface=Color(0xFF39312A),
    ErrorColor=Color(0xFFFFB4A5),
    ErrorSurface=Color(0xFF4A2927),
    WarningColor=Color(0xFFEDCA8C),
    SuccessColor=Color(0xFFBFD0BC),
    ProteinColor=Color(0xFFE1BACA),
    FatColor=Color(0xFFE5C296),
    CarbColor=Color(0xFFCFC1AD),
    ChartTrack=Color(0xFF51463A),
    ShadowColor=Color(0xFF000000),
    GlassEdge=Color(0xFFBBA38E),
    Caramel=Color(0xFFE3B084),
    Apricot=Color(0xFFE3B991),
    Pistachio=Color(0xFFD9C1A2),
    ProteinPastel=Color(0xFFDFCEBB)
)
val LocalWellnessPalette=staticCompositionLocalOf {LightPalette}
val PageBackground:Color @Composable get()=LocalWellnessPalette.current.PageBackground
val CardSurface:Color @Composable get()=LocalWellnessPalette.current.CardSurface
val SecondarySurface:Color @Composable get()=LocalWellnessPalette.current.SecondarySurface
val AccentBrown:Color @Composable get()=LocalWellnessPalette.current.AccentBrown
val TextPrimary:Color @Composable get()=LocalWellnessPalette.current.TextPrimary
val TextSecondary:Color @Composable get()=LocalWellnessPalette.current.TextSecondary
val BorderSoft:Color @Composable get()=LocalWellnessPalette.current.BorderSoft
val ControlOutline:Color @Composable get()=LocalWellnessPalette.current.ControlOutline
val SelectedSurface:Color @Composable get()=LocalWellnessPalette.current.SelectedSurface
val DisabledSurface:Color @Composable get()=LocalWellnessPalette.current.DisabledSurface
val ErrorColor:Color @Composable get()=LocalWellnessPalette.current.ErrorColor
val ErrorSurface:Color @Composable get()=LocalWellnessPalette.current.ErrorSurface
val WarningColor:Color @Composable get()=LocalWellnessPalette.current.WarningColor
val SuccessColor:Color @Composable get()=LocalWellnessPalette.current.SuccessColor
val ProteinColor:Color @Composable get()=LocalWellnessPalette.current.ProteinColor
val FatColor:Color @Composable get()=LocalWellnessPalette.current.FatColor
val CarbColor:Color @Composable get()=LocalWellnessPalette.current.CarbColor
val ChartTrack:Color @Composable get()=LocalWellnessPalette.current.ChartTrack
val ShadowColor:Color @Composable get()=LocalWellnessPalette.current.ShadowColor
val GlassEdge:Color @Composable get()=LocalWellnessPalette.current.GlassEdge
val Caramel:Color @Composable get()=LocalWellnessPalette.current.Caramel
val Apricot:Color @Composable get()=LocalWellnessPalette.current.Apricot
val Pistachio:Color @Composable get()=LocalWellnessPalette.current.Pistachio
val ProteinPastel:Color @Composable get()=LocalWellnessPalette.current.ProteinPastel
val PastelContent=Color(0xFF332A24)
val CameraSurface=Color(0xFF332A24)
val CameraContent=Color(0xFFFFFCF7)
internal fun wellnessColorScheme(p:WellnessPalette,dark:Boolean)= (if(dark) darkColorScheme() else lightColorScheme()).copy(
    primary=p.AccentBrown, onPrimary=if(dark) p.PageBackground else p.CardSurface,
    primaryContainer=p.SelectedSurface, onPrimaryContainer=p.TextPrimary,
    secondary=if(dark) p.Caramel else p.AccentBrown, onSecondary=if(dark) p.PageBackground else p.CardSurface,
    secondaryContainer=p.SecondarySurface, onSecondaryContainer=p.TextPrimary,
    tertiary=p.CarbColor, onTertiary=if(dark) p.PageBackground else p.CardSurface,
    tertiaryContainer=p.SecondarySurface, onTertiaryContainer=p.TextPrimary,
    background=p.PageBackground, onBackground=p.TextPrimary,
    surface=p.CardSurface, onSurface=p.TextPrimary,
    surfaceVariant=p.SecondarySurface, onSurfaceVariant=p.TextSecondary,
    surfaceContainerLowest=p.CardSurface, surfaceContainerLow=p.CardSurface,
    surfaceContainer=p.CardSurface, surfaceContainerHigh=p.CardSurface,
    surfaceContainerHighest=p.SecondarySurface, surfaceBright=p.CardSurface,
    surfaceDim=p.SecondarySurface, surfaceTint=Color.Transparent,
    outline=p.ControlOutline, outlineVariant=p.BorderSoft,
    error=p.ErrorColor, onError=p.CardSurface, errorContainer=p.ErrorSurface, onErrorContainer=p.ErrorColor,
    inverseSurface=p.TextPrimary, inverseOnSurface=p.CardSurface, inversePrimary=p.SelectedSurface,
    scrim=p.ShadowColor,
)

// Unmodified local variable fonts; only regular and semibold instances.
@OptIn(ExperimentalTextApi::class)
private fun family(resource:Int)=FontFamily(
    Font(resource,weight=FontWeight.Normal,variationSettings=FontVariation.Settings(FontVariation.weight(400))),
    Font(resource,weight=FontWeight.SemiBold,variationSettings=FontVariation.Settings(FontVariation.weight(600))),
)
private val InterFont=family(R.font.inter)
private val LoraFont=family(R.font.lora)
private val BodoniFont=FontFamily(Font(R.font.bodoni_moda,FontWeight.Normal))
val NotoFallback=family(R.font.noto_sans)
private val LocalHeading=staticCompositionLocalOf {LoraFont}
val BodyFont=InterFont
val HeadingFont:FontFamily @Composable get()=LocalHeading.current
private fun text(size:Int,height:Int,weight:FontWeight=FontWeight.Normal,heading:Boolean=false,spacing:Float=0f,head:FontFamily=LoraFont)=
    TextStyle(fontFamily=if(heading) head else BodyFont,fontSize=size.sp,lineHeight=height.sp,
        fontWeight=if(heading) FontWeight.Normal else weight,letterSpacing=spacing.sp,
        fontFeatureSettings=if(heading) null else "tnum")
private fun typography(head:FontFamily)=Typography(
    displayLarge=text(48,58,FontWeight.SemiBold,spacing=-1f),
    displayMedium=text(44,54,FontWeight.SemiBold,spacing=-.8f),
    displaySmall=text(40,50,FontWeight.SemiBold,spacing=-.6f),
    headlineLarge=text(32,42,FontWeight.SemiBold,true,head=head),
    headlineMedium=text(26,35,FontWeight.SemiBold,true,head=head),
    headlineSmall=text(22,30,FontWeight.SemiBold,true,head=head),
    titleLarge=text(19,27,FontWeight.SemiBold,true,head=head),
    titleMedium=text(16,23,FontWeight.SemiBold),titleSmall=text(14,21,FontWeight.SemiBold),
    bodyLarge=text(16,24),bodyMedium=text(14,21),bodySmall=text(12,18),
    labelLarge=text(14,20,FontWeight.SemiBold),labelMedium=text(12,18,FontWeight.SemiBold),labelSmall=text(11,16,FontWeight.SemiBold),
)
@Composable fun TamaqTheme(darkTheme:Boolean?=null,content:@Composable ()->Unit) {
    val preferences=(LocalContext.current.applicationContext as TamaqApplication).preferences
    val mode by preferences.theme.collectAsStateWithLifecycle()
    val dark=darkTheme ?: when(mode) {ThemeMode.Dark->true;ThemeMode.Light->false;ThemeMode.System->isSystemInDarkTheme()}
    val palette=if(dark) DarkPalette else LightPalette
    val language=LocalConfiguration.current.locales[0].language
    val heading=if(language in listOf("ru","kk")) LoraFont else BodoniFont
    MaterialTheme(colorScheme=wellnessColorScheme(palette,dark),typography=typography(heading),
        shapes=Shapes(extraSmall=RoundedCornerShape(12.dp),small=RoundedCornerShape(16.dp),
            medium=RoundedCornerShape(24.dp),large=RoundedCornerShape(28.dp),extraLarge=RoundedCornerShape(32.dp))) {
        CompositionLocalProvider(LocalWellnessPalette provides palette,LocalHeading provides heading,
            LocalContentColor provides palette.TextPrimary) {content()}
    }
}
