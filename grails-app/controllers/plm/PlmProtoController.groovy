package plm

import grails.compiler.GrailsCompileStatic
import grails.gorm.transactions.Transactional
import grails.plugin.springsecurity.annotation.Secured
import org.springframework.web.multipart.MultipartFile
import org.springframework.web.multipart.MultipartRequest

import java.nio.file.Files
import java.util.zip.ZipFile

@GrailsCompileStatic
@Secured(["ROLE_PLM_USER", "ROLE_ADMIN"])
class PlmProtoController {

    static scope = "session"

    PlmFreeCadProtoService plmFreeCadProtoService

    @Transactional
    def uploadProto() {
        MultipartFile proto = (request as MultipartRequest).getFile('proto.bin')
        File zipProto = Files.createTempFile("proto", "zip").toFile()
        proto.transferTo(zipProto)
        try (var zipFile = new ZipFile(zipProto)) {
            response.status = 200
            response.contentType = 'application/octet-stream'
            response.outputStream << plmFreeCadProtoService.processZippedProto(zipFile).toByteArray()
            response.outputStream.flush()
            response.outputStream.close()
        } catch (IOException e) {
            log.error "${e.toString()}"
            plmFreeCadProtoService.incomingBucket = null
            response.status = 200
            response.contentType = 'application/octet-stream'
            response.outputStream << plmFreeCadProtoService.outbound.build().toByteArray()
            response.outputStream.flush()
            response.outputStream.close()
        }
    }

    @Transactional
    def uploadZip() {
        MultipartFile proto = (request as MultipartRequest).getFile('proto.bin')
        File zipProto = Files.createTempFile("proto", "zip").toFile()
        proto.transferTo(zipProto)
        try (var zipFile = new ZipFile(zipProto)) {
            response.status = 200
            response.contentType = 'application/octet-stream'
            response.outputStream << plmFreeCadProtoService.processZippedFiles(zipFile).toByteArray()
            response.outputStream.flush()
            response.outputStream.close()
        } catch (IOException e) {
            log.error "${e.toString()}"
            plmFreeCadProtoService.incomingBucket = null
            response.status = 200
            response.contentType = 'application/octet-stream'
            response.outputStream << plmFreeCadProtoService.outbound.build().toByteArray()
            response.outputStream.flush()
            response.outputStream.close()
        }
    }

    def reset() {
        plmFreeCadProtoService.incomingBucket = null
        render "OK"
    }
}