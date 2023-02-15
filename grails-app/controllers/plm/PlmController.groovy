package plm

import grails.compiler.GrailsCompileStatic
import grails.gorm.transactions.Transactional
import grails.plugin.springsecurity.annotation.Secured
import grails.web.api.WebAttributes
import org.codehaus.groovy.runtime.MethodClosure
import org.springframework.web.multipart.MultipartRequest
import taack.ast.type.FieldInfo
import taack.base.TaackMetaModelService
import taack.base.TaackSimpleSaveService
import taack.base.TaackUiSimpleService
import taack.ui.base.UiBlockSpecifier
import taack.ui.base.UiMenuSpecifier
import taack.ui.base.block.BlockSpec
import taack.ui.base.common.ActionIcon

@GrailsCompileStatic
@Secured(["ROLE_PLM_USER", "ROLE_ADMIN"])
class PlmController implements WebAttributes {
    TaackUiSimpleService taackUiSimpleService
    PlmUiService plmUiService
    PlmFreeCadUiService plmFreeCadUiService
    TaackMetaModelService taackMetaModelService
    TaackSimpleSaveService taackSimpleSaveService

    protected final String tr(final String code, final Locale locale = null) {
        plmUiService.tr(code, locale)
    }

    private UiMenuSpecifier buildMenu() {
        new UiMenuSpecifier().ui {
            menu tr("plm.parts.menu"), this.&parts as MethodClosure
            menu 'Locked Parts', this.&lockedParts as MethodClosure
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
        response.setHeader("Content-disposition", "filename=\"${URLEncoder.encode("${part.originalName}${partVersion ? "-v${partVersion}": ''}-${new Date().format('yyyyMMdd:hh:mm:ss')}.zip", 'UTF-8')}\"")
        response.outputStream << plmFreeCadUiService.zipPart(part, partVersion).bytes
        response.outputStream.close()
    }

    def parts() {
        taackUiSimpleService.show(new UiBlockSpecifier().ui {
            ajaxBlock("parts", {
                tableFilter("Filter", plmFreeCadUiService.buildPartFilter(), "Results", plmFreeCadUiService.buildPartTable(), BlockSpec.Width.MAX, {
                    action "Graph", ActionIcon.GRAPH, this.&model as MethodClosure, true
                })
            })
        }, buildMenu())
    }

    def lockedParts() {
        render 'Not done Yet ..'
    }

    def showPart(PlmFreeCadPart part, Long partVersion) {
        taackUiSimpleService.show(plmFreeCadUiService.buildFreeCadPartBlockShow(part, partVersion, params.boolean('isHistory')), buildMenu())
    }

    def previewPart(PlmFreeCadPart part, Long partVersion) {
        response.setContentType("image/webp")
        response.setHeader("Content-disposition", "filename=\"${URLEncoder.encode(part?.originalName + '-' + partVersion + '.webp', "UTF-8")}\"")
        response.setHeader("Cache-Control", "max-age=604800")
        response.outputStream << (plmFreeCadUiService.preview(part, partVersion)).bytes
        return false
    }

    def editPart(PlmFreeCadPart part) {
        taackUiSimpleService.show(new UiBlockSpecifier().ui {
            modal {
                ajaxBlock("editIssue", {
                    form "Issue", plmFreeCadUiService.buildPartForm(part)
                })
            }
        }, buildMenu())
    }

    @Transactional
    def savePart() {
        def p = new PlmFreeCadPart()
        taackSimpleSaveService.saveThenReloadOrRenderErrors(PlmFreeCadPart, [null, p.commentVersion_, p.status_] as FieldInfo[])
    }

    def model() {
        String graph = taackMetaModelService.buildEnumTransitionGraph(PlmFreeCadPartStatus.CREATED)
        taackUiSimpleService.show(new UiBlockSpecifier().ui {
            modal {
                ajaxBlock "model", {
                    custom "Graph", taackMetaModelService.svg(graph)
                }
            }
        })
    }

}