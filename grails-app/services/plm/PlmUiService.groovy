package plm

import grails.compiler.GrailsCompileStatic
import org.springframework.context.MessageSource
import org.springframework.context.i18n.LocaleContextHolder

@GrailsCompileStatic
class PlmUiService {
    MessageSource messageSource

    protected String tr(final String code, final Locale locale = null, final Object[] args = null) {
        if (LocaleContextHolder.locale.language == "test") return code
        try {
            messageSource.getMessage(code, args, locale ?: LocaleContextHolder.locale)
        } catch (e1) {
            try {
                messageSource.getMessage(code, args, new Locale("en"))
            } catch (e2) {
                code
            }
        }
    }

}
