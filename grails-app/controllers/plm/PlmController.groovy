package plm

import attachement.AttachmentUiService
import crew.config.SupportedLanguage
import grails.compiler.GrailsCompileStatic
import grails.gorm.transactions.Transactional
import grails.plugin.springsecurity.annotation.Secured
import grails.web.api.WebAttributes
import org.codehaus.groovy.runtime.MethodClosure
import org.codehaus.groovy.runtime.MethodClosure as MC
import org.springframework.web.multipart.MultipartRequest
import attachment.Attachment
import taack.ast.type.FieldInfo
import taack.domain.TaackAttachmentService
import taack.domain.TaackMetaModelService
import taack.domain.TaackSaveService
import taack.render.TaackUiService
import taack.ui.dsl.UiBlockSpecifier
import taack.ui.dsl.UiFormSpecifier
import taack.ui.dsl.UiMenuSpecifier
import taack.ui.dsl.common.ActionIcon

@GrailsCompileStatic
@Secured(["ROLE_PLM_USER", "ROLE_ADMIN"])
class PlmController implements WebAttributes {
    TaackUiService taackUiService
    PlmFreeCadUiService plmFreeCadUiService
    TaackMetaModelService taackMetaModelService
    TaackSaveService taackSaveService
    PlmSearchService plmSearchService
    TaackAttachmentService taackAttachmentService

    private UiMenuSpecifier buildMenu(String q = null) {
        new UiMenuSpecifier().ui {
            menu this.&parts as MC
            menu this.&lockedParts as MC
            menuSearch this.&search as MethodClosure, q
            menuOptions(SupportedLanguage.fromContext())

        }
    }

    def index() {
        if (PlmFreeCadUiService.errorsInit.size() > 0)
            render('Following errors occurs when checking binaries:<br>' + PlmFreeCadUiService.errorsInit.join('<br>'))
        else
            redirect action: 'parts'
    }

    @Transactional
    def uploadProto() {
        def proto = (request as MultipartRequest).getFile('proto.bin')
        render plmFreeCadUiService.processProto(proto.bytes)
    }

    def downloadBinPart(PlmFreeCadPart part, Long partVersion) {
        response.contentType = 'application/zip'
        response.setHeader("Content-disposition", "filename=${URLEncoder.encode("${part.originalName}${partVersion ? "-v${partVersion}" : ''}-${TaackUiService.dateFileName}.zip", 'UTF-8')}")
        response.outputStream << plmFreeCadUiService.zipPart(part, partVersion).bytes
        response.outputStream.close()
    }

    def parts() {
        taackUiService.show(new UiBlockSpecifier().ui {
            tableFilter(plmFreeCadUiService.buildPartFilter(), plmFreeCadUiService.buildPartTable(), {
                menuIcon ActionIcon.GRAPH, this.&model as MC
            })
        }, buildMenu())
    }

    def lockedParts() {
        render 'Not done Yet ..'
    }

    @Transactional
    def saveComment() {
        taackSaveService.saveThenReloadOrRenderErrors(PlmFreeCadPart)
    }

    def addComment(PlmFreeCadPart part) {
        taackUiService.show(new UiBlockSpecifier().ui {
            modal {
                form(new UiFormSpecifier().ui(part) {
                    section {
                        field part.commentVersion_
                    }
                    formAction this.&saveComment as MC
                }) {
                    label('coucou')
                }
            }
        })
    }

    def showPart(PlmFreeCadPart part, Long partVersion, Boolean isHistory) {
        taackUiService.show(
                plmFreeCadUiService.buildFreeCadPartBlockShow(
                        part, partVersion, false, isHistory),
                buildMenu(),
                "isHistory")
    }

    def previewPart(PlmFreeCadPart part, Long partVersion, String timestamp) {
        response.setContentType("image/webp")
        response.setHeader("Content-disposition", "filename=\"${URLEncoder.encode(part?.originalName + '-' + partVersion + '-' + timestamp + '.webp', "UTF-8")}\"")
        response.setHeader("Cache-Control", "max-age=604800")
        response.outputStream << (plmFreeCadUiService.preview(part, partVersion)).bytes
        return false
    }

    def editPart(PlmFreeCadPart part) {
        taackUiService.show(new UiBlockSpecifier().ui {
            modal {
                form plmFreeCadUiService.buildPartForm(part)
            }
        }, buildMenu())
    }

    @Transactional
    def savePart() {
        def p = new PlmFreeCadPart()
        taackSaveService.saveThenReloadOrRenderErrors(PlmFreeCadPart, [null, p.commentVersion_, p.status_, p.tags_, p.computedVersion_] as FieldInfo[])
    }

    def model() {
        String graph = taackMetaModelService.buildEnumTransitionGraph(PlmFreeCadPartStatus.CREATED)
        taackUiService.show(new UiBlockSpecifier().ui {
            modal {
                custom taackMetaModelService.svg(graph)
            }
        })
    }

    def addAttachment(PlmFreeCadPart part) {
        taackUiService.show(new UiBlockSpecifier().ui {
            modal {
                form AttachmentUiService.buildAttachmentForm(
                        new Attachment(),
                        this.&saveAttachment as MC,
                        [id: part.id])
            }
        })
    }

    @Transactional
    def saveAttachment() {
        def p = PlmFreeCadPart.get(params.long('objectId'))
        def att = taackSaveService.save(Attachment)
        p.addToCommentVersionAttachmentList(att)
        taackUiService.ajaxReload()
    }

    def search(String q) {
        taackUiService.show(plmSearchService.buildSearchBlock(q), buildMenu(q))
    }

    def downloadBinCommentVersionFiles(PlmFreeCadPart part, String path) {
        part = part.nextVersion ?: part
        Attachment a = part.commentVersionAttachmentList.find {
            it.originalName == path.substring(1)
        }
println part
println part.commentVersionAttachmentList
        if (a) {
            taackAttachmentService.downloadAttachment(a)
        }
        return false
    }
}