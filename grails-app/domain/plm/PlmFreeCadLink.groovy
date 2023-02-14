package plm

import grails.compiler.GrailsCompileStatic
import org.taack.User
import taack.ast.annotation.TaackFieldEnum

enum PlmFreeCadLinkCopyOnChange {
    DISABLED, ENABLED, OWNED
}
@GrailsCompileStatic
@TaackFieldEnum
class PlmFreeCadLink {
    Date dateCreated
    Date lastUpdated

    User userCreated
    User userUpdated

    String linkedObject
    Boolean linkClaimChild
    Boolean linkTransform

    PlmFreeCadLinkCopyOnChange linkCopyOnChange

    PlmFreeCadPart parentPart
    PlmFreeCadPart part
    Long partLinkVersion

    static constraints = {
    }
}
