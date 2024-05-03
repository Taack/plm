package plm

import attachement.AttachmentUiService
import grails.compiler.GrailsCompileStatic
import grails.gorm.transactions.Transactional
import grails.plugin.springsecurity.annotation.Secured
import grails.web.api.WebAttributes
import org.codehaus.groovy.runtime.MethodClosure as MC
import org.springframework.web.multipart.MultipartRequest
import org.taack.Attachment
import taack.ast.type.FieldInfo
import taack.domain.TaackMetaModelService
import taack.domain.TaackSaveService
import taack.render.TaackUiService
import taack.ui.base.UiBlockSpecifier
import taack.ui.base.UiMenuSpecifier
import taack.ui.base.block.BlockSpec
import taack.ui.base.common.ActionIcon

@GrailsCompileStatic
@Secured(["ROLE_PLM_USER", "ROLE_ADMIN"])
class PlmController implements WebAttributes {
    TaackUiService taackUiService
    PlmFreeCadUiService plmFreeCadUiService
    TaackMetaModelService taackMetaModelService
    TaackSaveService taackSaveService

    private UiMenuSpecifier buildMenu() {
        new UiMenuSpecifier().ui {
            menu this.&parts as MC
            menu this.&lockedParts as MC
        }
    }

    def index() {
        redirect action: 'parts'
    }

    @Transactional
    def uploadProto() {
        def proto = (request as MultipartRequest).getFile('proto.bin')
        render plmFreeCadUiService.processProto(proto.bytes)
    }

    def downloadPart(PlmFreeCadPart part, Long partVersion) {
        response.contentType = 'application/zip'
        response.setHeader("Content-disposition", "filename=\"${URLEncoder.encode("${part.originalName}${partVersion ? "-v${partVersion}" : ''}-${TaackUiService.dateFileName}.zip", 'UTF-8')}\"")
        response.outputStream << plmFreeCadUiService.zipPart(part, partVersion).bytes
        response.outputStream.close()
    }

    def parts() {
        taackUiService.show(new UiBlockSpecifier().ui {
            tableFilter("Filter", plmFreeCadUiService.buildPartFilter(), "Results", plmFreeCadUiService.buildPartTable(), BlockSpec.Width.MAX, {
                action ActionIcon.GRAPH, this.&model as MC
            })
        }, buildMenu())
    }

    def lockedParts() {
        render 'Not done Yet ..'
    }

    def showPart(PlmFreeCadPart part, Long partVersion, Boolean isHistory) {
        if (!isHistory) {
            params.remove('isAjax') // TODO: Avoid that ...
            taackUiService.show(plmFreeCadUiService.buildFreeCadPartBlockShow(part, partVersion, false, isHistory), buildMenu())
        } else {
            taackUiService.show(plmFreeCadUiService.buildFreeCadPartBlockShow(part, partVersion, false, isHistory))
        }
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
                custom "Graph", taackMetaModelService.svg(graph)
            }
        })
    }

    def addAttachment(PlmFreeCadPart part) {
        taackUiService.show(new UiBlockSpecifier().ui {
            modal {
                form AttachmentUiService.buildAttachmentForm(
                        new Attachment(),
                        this.&saveAttachment as MC,
                        [id: part.id]),
                        BlockSpec.Width.MAX
            }
        })
    }

    @Transactional
    def saveAttachment() {
        def p = PlmFreeCadPart.get(params.long('objectId'))
        def att = taackSaveService.save(Attachment)
        p.addToAttachments(att)
        taackUiService.ajaxReload()
    }

}