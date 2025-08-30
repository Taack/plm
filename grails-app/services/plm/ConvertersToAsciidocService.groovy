package plm

import attachment.Attachment
import grails.compiler.GrailsCompileStatic
import taack.domain.TaackAttachmentService
import taack.render.TaackEditorService

@GrailsCompileStatic
class ConvertersToAsciidocService {

    TaackEditorService taackEditorService
    TaackAttachmentService taackAttachmentService

    final String saveImage(PlmFreeCadPart part, String path, byte[] image) {
        Attachment a = taackAttachmentService.createAttachment(path, image)

        part.addToCommentVersionAttachmentList(a)
        a.originalName
    }

    String convert(PlmFreeCadPart page, InputStream inputStream) {
        taackEditorService.convert(new TaackEditorService.ISaveImage() {
            @Override
            String saveImage(String imagePath, byte[] image) {
                return saveImage(page, imagePath, image)
            }
        }, inputStream)
    }

    String convertFromHtml(String html) {
        taackEditorService.convertFromHtml(html)
    }
}
