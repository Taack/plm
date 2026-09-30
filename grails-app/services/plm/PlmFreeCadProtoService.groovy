package plm


import attachment.DocumentAccess
import attachment.DocumentCategory
import attachment.config.DocumentCategoryEnum
import crew.User
import grails.compiler.GrailsCompileStatic
import grails.converters.JSON
import grails.plugin.springsecurity.SpringSecurityService
import plm.freecad.FreecadPlm
import plm.freecad.FreecadPlm.PlmFile
import taack.ui.TaackUiConfiguration

import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import java.security.DigestInputStream
import java.security.MessageDigest
import java.text.SimpleDateFormat
import java.util.zip.ZipEntry
import java.util.zip.ZipFile

@GrailsCompileStatic
class PlmFreeCadProtoService {

    SpringSecurityService springSecurityService

    final private String intranetRoot = TaackUiConfiguration.root

    String getStorePath() {
        intranetRoot + "/plmFreecad/model"
    }

    String getPreviewPath() {
        intranetRoot + "/plmFreecad/preview"
    }

    FreecadPlm.Bucket incomingBucket = null

    FreecadPlm.Bucket processProto(ZipFile zipFile) {
        FreecadPlm.Bucket.Builder outbound = FreecadPlm.Bucket.newBuilder()
        outbound.setStatus(FreecadPlm.ServerStatus.NOK_PROTO)

        var protoBin = zipFile.getEntry("proto.bin")
        incomingBucket = FreecadPlm.Bucket.parseFrom(zipFile.getInputStream(protoBin))
        outbound.setStatus(FreecadPlm.ServerStatus.OK_PROTO)
        for (PlmFile plmFile in incomingBucket.plmFilesMap.values()) {
            PlmFreeCadPart existingPart = PlmFreeCadPart.findByPlmContentShaOne(plmFile.sha1Hex)
            if (existingPart) {
                log.info "existingPart for ${plmFile.sha1Hex}"
                outbound.addServerSha1Files(plmFile.sha1Hex)
            } else {
                log.info "no existingPart for ${plmFile.sha1Hex}"
            }
        }
        log.info "return outbound ..."
        return outbound.build()
    }

    FreecadPlm.Bucket processZip(ZipFile zipFile) {
        FreecadPlm.Bucket.Builder outbound = FreecadPlm.Bucket.newBuilder()
        outbound.setStatus(FreecadPlm.ServerStatus.NOK_FILES)
        Map<String, FreecadPlm.PlmLink> linksMap = incomingBucket.linksMap
        Map<String, PlmFile> plmFilesMap = incomingBucket.plmFilesMap
        User u = springSecurityService.currentUser as User
        Map<String, PlmFreeCadPart> objNameToPart = [:]
        Map<String, List<PlmFreeCadPart>> partLinkedPartNameToParts = [:]
        SimpleDateFormat dateFormat = new SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ssX")
        dateFormat.setTimeZone(TimeZone.getTimeZone("UTC"))
        plmFilesMap.each { Map.Entry<String, PlmFile> entryIt ->
            PlmFile plmFile = entryIt.value
            byte[] fileContent = null
            InputStream fileContentIs = null
            String sha1 = null
            if (!plmFile.fileContent.isEmpty()) {
                fileContent = plmFile.fileContent.toByteArray()
                sha1 = MessageDigest.getInstance('SHA1').digest(fileContent).encodeHex().toString()
            } else {
                sha1 = plmFile.sha1Hex
                ZipEntry entry = zipFile.getEntry(sha1)
                if (entry) {
                    InputStream zipFileContentIs = zipFile.getInputStream(entry)
                    MessageDigest digest = MessageDigest.getInstance("SHA1")
                    try (DigestInputStream dis = new DigestInputStream(zipFileContentIs, digest)) {
                        byte[] buffer = new byte[8192]
                        while (dis.read(buffer) != -1) {
                        }
                    }
                    String computedSha1 = digest.digest().encodeHex().toString()
                    if (computedSha1 != sha1) {
                        log.warn("Sha1($sha1) != computedSha1($computedSha1)")
                        return [success: false, message: 'NOK'] as JSON
                    }
                    fileContentIs = zipFile.getInputStream(zipFile.getEntry(sha1))
                    if (!plmFile.filePreview.isEmpty()) {
                        Path filePreviewPath = Paths.get(previewPath, sha1 + '.png')
                        filePreviewPath.toFile() << plmFile.filePreview.toByteArray()
                    }
                } else {
                    log.info "Entry null for $sha1"
                }
            }
            PlmFreeCadPart existingPart = PlmFreeCadPart.findByPlmContentShaOne(sha1)
            String ext = plmFile.fileName.substring(plmFile.fileName.lastIndexOf('.') + 1)

            if (plmFile.id == null || plmFile.id.isBlank()) {
                log.error "PlmFile without ID: ${plmFile.name} $existingPart"
                return ([success: false, message: "PlmFile without ID: ${plmFile.name} $existingPart"] as JSON)
            } else if (plmFile.fileName.contains('"')) {
                log.error "PlmFile fileName contains double quotes: ${plmFile.fileName} $existingPart"
                return ([success: false, message: "PlmFile label contains double quotes: ${plmFile.label} $existingPart"] as JSON)
            } else {
                PlmFreeCadPart partToBeCloned = PlmFreeCadPart.findByFileIdAndNextVersionIsNull(plmFile.id)
                log.info "Upload PlmFile: ${plmFile.name} with id: ${plmFile.id}, already exists: ${existingPart}, part to be cloned ${partToBeCloned}"
                if (!existingPart) {
                    if (!partToBeCloned) {
                        partToBeCloned = new PlmFreeCadPart()
                        partToBeCloned.userCreated = u
                    } else {
                        PlmFreeCadPart oldPart = partToBeCloned.cloneDirectObjectData()
                        partToBeCloned.plmLinks?.each { PlmFreeCadLink lIt ->
                            oldPart.addToPlmLinks(lIt)
                        }
                        oldPart.userUpdated = u
                        oldPart.save(flush: true)
                        if (oldPart.hasErrors()) log.error "${oldPart.errors}"

                    }
                    partToBeCloned.userUpdated = u
                    File file = new File(storePath + '/' + sha1 + '.' + ext)
                    if (fileContent) file << fileContent
                    if (fileContentIs) file << fileContentIs
                    partToBeCloned.plmFilePath = sha1 + '.' + ext
                    partToBeCloned.pathOnHost = plmFile.fileName
                    partToBeCloned.fileId = plmFile.id
                    partToBeCloned.comment = plmFile.comment
                    partToBeCloned.label = plmFile.label
                    partToBeCloned.plmFileLastUpdated = dateFormat.parse(plmFile.lastModifiedDate)
                    partToBeCloned.plmFileDateCreated = dateFormat.parse(plmFile.createdDate)
                    partToBeCloned.plmFileUserCreated = plmFile.createdBy
                    partToBeCloned.plmFileUserUpdated = plmFile.lastModifiedBy
                    partToBeCloned.plmContentType = Files.probeContentType(file.toPath())
                    partToBeCloned.plmContentShaOne = sha1
                    partToBeCloned.originalName = plmFile.name
                    partToBeCloned.cTimeNs = plmFile.getCTimeNs()
                    partToBeCloned.mTimeNs = plmFile.getUTimeNs()

                    DocumentAccess documentAccess = DocumentAccess.findOrCreateByIsInternalAndIsRestrictedToMyBusinessUnitAndIsRestrictedToMySubsidiaryAndIsRestrictedToMyManagersAndIsRestrictedToEmbeddingObjects(false, false, false, false, true)
                    DocumentCategory documentCategory = new DocumentCategory(category: DocumentCategoryEnum.OTHER)

                    partToBeCloned.documentCategory = documentCategory
                    partToBeCloned.documentAccess = documentAccess
                    partToBeCloned.save(flush: true, failOnError: true)
                    if (partToBeCloned.hasErrors()) log.error "${partToBeCloned.errors}"
                }
                objNameToPart.put(plmFile.name, existingPart ?: partToBeCloned)
                plmFile.externalLinkList.each { String lIt ->
                    partLinkedPartNameToParts[lIt] ?= []
                    partLinkedPartNameToParts[lIt].add(existingPart ?: partToBeCloned)
                }
            }
        }
        linksMap.each { entry ->
            PlmFreeCadPart part = objNameToPart[entry.key]
            if (part) {
                partLinkedPartNameToParts[entry.key]?.each { parent ->
                    if (parent.id == part.id) {
                        log.warn("Cyclic dependency for part ${part}")
                        return
                    }
                    PlmFreeCadLink link = PlmFreeCadLink.findByPartAndParentPart(part, parent)
                    if (!link) {
                        link = new PlmFreeCadLink(part: part, partLinkVersion: part.computedVersion, parentPart: parent, userCreated: u)
                    }
                    link.linkedObject = entry.value.linkedObject
                    link.userUpdated = u
                    link.linkTransform = entry.value.linkTransform
                    link.linkClaimChild = entry.value.linkClaimChild

                    switch (entry.value.linkCopyOnChange) {
                        case FreecadPlm.PlmLink.LinkCopyOnChangeEnum.Disabled:
                            link.linkCopyOnChange = PlmFreeCadLinkCopyOnChange.DISABLED
                            break
                        case FreecadPlm.PlmLink.LinkCopyOnChangeEnum.Enabled:
                            link.linkCopyOnChange = PlmFreeCadLinkCopyOnChange.ENABLED
                            break
                        case FreecadPlm.PlmLink.LinkCopyOnChangeEnum.Owned:
                            link.linkCopyOnChange = PlmFreeCadLinkCopyOnChange.OWNED
                            break
                        case FreecadPlm.PlmLink.LinkCopyOnChangeEnum.UNRECOGNIZED:
                            log.error 'FreecadPlm.PlmLink.LinkCopyOnChangeEnum.UNRECOGNIZED'
                            break
                    }
                    link.save(flush: true, failOnError: true)
                    if (link.hasErrors()) log.error "${link.errors}"
                }
            } else {
                log.error("No part for ${entry.key} in protobuf !!!")
            }
        }
        outbound.setStatus(FreecadPlm.ServerStatus.OK_FILES)
        return outbound.build()
    }
}