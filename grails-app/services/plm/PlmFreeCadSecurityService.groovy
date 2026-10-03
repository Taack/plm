package plm

import attachement.AttachmentSecurityService
import attachment.WriteAccess
import crew.User
import grails.plugin.springsecurity.SpringSecurityService
import jakarta.annotation.PostConstruct
import org.codehaus.groovy.runtime.MethodClosure
import taack.app.TaackApp
import taack.app.TaackAppRegisterService
import taack.render.TaackUiEnablerService

class PlmFreeCadSecurityService {

    static lazyInit = false

    SpringSecurityService springSecurityService

    private securityCanDownloadClosure(Long id, Map p) {
        if (!id && !p) return true
        if (!id) return true
        canDownloadFile(PlmFreeCadPart.get(id), springSecurityService.currentUser as User)
    }

    private securityCanEditClosure(Long id, Map p) {
        if (!id && !p) return true
        if (!id) return true
        canEditFile(PlmFreeCadPart.get(id), springSecurityService.currentUser as User)
    }

    @PostConstruct
    void init() {
        TaackUiEnablerService.securityClosure(
                this.&securityCanDownloadClosure,
                PlmController.&downloadBinPart as MethodClosure,
                PlmController.&addComment as MethodClosure,
                PlmController.&previewPart as MethodClosure,
                PlmController.&editPart as MethodClosure,
                PlmController.&editPartCategory as MethodClosure,
                PlmController.&previewPart as MethodClosure,
                PlmController.&previewAsciidoc as MethodClosure,
                PlmController.&showPart as MethodClosure,
                PlmController.&saveComment as MethodClosure
        )
        TaackUiEnablerService.securityClosure(
                this.&securityCanEditClosure,
                PlmController.&editPart as MethodClosure,
                PlmController.&editPartCategory as MethodClosure,
                PlmController.&savePart as MethodClosure,
                PlmController.&addAttachment as MethodClosure,
                PlmController.&saveAttachment as MethodClosure,
                PlmController.&importAttachment as MethodClosure,
                PlmController.&savePartCategory as MethodClosure,
                PlmController.&saveComment as MethodClosure
        )

        TaackAppRegisterService.register(new TaackApp(PlmController.&parts as MethodClosure, new String(this.class.getResourceAsStream("/plm/plm.svg").readAllBytes())))
    }


    boolean canEditFile(PlmFreeCadPart plmDoc, User user) {
        switch (plmDoc.writeAccess) {
            case WriteAccess.OWNERS:
                return plmDoc.userCreated.id == user.id || plmDoc.userCreated.allManagers*.id.contains(user.id)
                break
            case WriteAccess.READ_ONLY:
                return false
                break
            case WriteAccess.READERS:
                return canDownloadFile(plmDoc, user)
                break
        }
        return plmDoc.userCreated.id == user.id
    }

    boolean canDownloadFile(PlmFreeCadPart plmDoc) {
        canDownloadFile(plmDoc, springSecurityService.currentUser as User)
    }

    boolean canDownloadFile(PlmFreeCadPart plmDoc, User user) {
        if (plmDoc.nextVersion) plmDoc = plmDoc.nextVersion
        if (user == plmDoc.userCreated) return true
        if (!plmDoc.documentAccess) return true
        return AttachmentSecurityService.canDownloadFile(plmDoc.documentAccess, plmDoc.userCreated, user)
    }

}
