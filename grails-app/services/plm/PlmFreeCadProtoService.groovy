package plm

import attachment.DocumentAccess
import attachment.DocumentCategory
import attachment.config.DocumentCategoryEnum
import crew.User
import grails.compiler.GrailsCompileStatic
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

import static taack.render.TaackUiService.tr

@GrailsCompileStatic
class PlmFreeCadProtoService {

    SpringSecurityService springSecurityService
    PlmFreeCadSecurityService plmFreeCadSecurityService

    final private String intranetRoot = TaackUiConfiguration.root

    String getStorePath() {
        intranetRoot + "/plmFreecad/model"
    }

    String getPreviewPath() {
        intranetRoot + "/plmFreecad/preview"
    }

    FreecadPlm.Bucket incomingBucket = null
    FreecadPlm.Bucket.Builder outbound = null

    FreecadPlm.Bucket processZippedProto(ZipFile zipFile) {
        outbound = FreecadPlm.Bucket.newBuilder()
        outbound.setStatus(FreecadPlm.ServerStatus.NOK_PROTO)

        var protoBin = zipFile.getEntry("proto.bin")
        incomingBucket = FreecadPlm.Bucket.parseFrom(zipFile.getInputStream(protoBin))
        outbound.setStatus(FreecadPlm.ServerStatus.OK_PROTO)
        for (PlmFile plmFile in incomingBucket.plmFilesMap.values()) {
            PlmFreeCadPart existingPart = PlmFreeCadPart.findByPlmContentShaOne(plmFile.sha1Hex)
            if (existingPart) {
                log.info "existingPart for ${plmFile.sha1Hex}"
                if (plmFile.label != existingPart.label) {
                    log.warn "Part label becomes ${plmFile.label}"
                    existingPart.label = plmFile.label
                }
                if (plmFile.fileName != existingPart.pathOnHost) {
                    log.warn "Part path becomes ${plmFile.fileName}"
                    existingPart.pathOnHost = plmFile.fileName
                }
                outbound.addServerSha1Files(plmFile.sha1Hex)
            } else {
                log.info "no existingPart for ${plmFile.sha1Hex}"
            }
        }
        log.info "return outbound ..."
        return outbound.build()
    }

    FreecadPlm.Bucket processZippedFiles(ZipFile zipFile) {
        outbound = FreecadPlm.Bucket.newBuilder()
        outbound.setStatus(FreecadPlm.ServerStatus.NOK_FILES)
        Map<String, FreecadPlm.PlmLink> linksMap = incomingBucket.linksMap
        Map<String, PlmFile> plmFilesMap = incomingBucket.plmFilesMap
        User u = springSecurityService.currentUser as User
        Map<String, PlmFreeCadPart> objNameToPart = [:]
        Map<String, List<PlmFreeCadPart>> partLinkedPartNameToParts = [:]
        SimpleDateFormat dateFormat = new SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ssX")
        dateFormat.setTimeZone(TimeZone.getTimeZone("UTC"))
        for (Map.Entry<String, PlmFile> entryIt in plmFilesMap) {
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
                        outbound.uploadError = tr("received.sha1.different.from.computed.sha1.error", sha1, computedSha1)
                        return outbound.build()
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

            if (existingPart?.status == PlmFreeCadPartStatus.LOCKED) {
                log.error "Attempt to update Locked Part (from sha1): ${plmFile.name} $existingPart"
                outbound.uploadError = tr("attempt.to.update.locked.part.with.same.sha1.error", plmFile.name)
                return outbound.build()
            } else if (existingPart && !plmFreeCadSecurityService.canEditFile(existingPart, u)) {
                log.error "Attempt to update part you are not supposed to edit (from sha1): ${plmFile.name} $existingPart"
                outbound.uploadError = tr("attempt.to.update.a.part.with.same.sha1.you.are.not.supposed.to.edit.error", plmFile.name)
                return outbound.build()
            } else if (plmFile.id == null || plmFile.id.isBlank()) {
                log.error "PlmFile without ID: ${plmFile.name} $existingPart"
                outbound.uploadError = tr("plm.file.without.id.error", plmFile.name)
                return outbound.build()
            } else if (plmFile.fileName.contains('"')) {
                log.error "PlmFile fileName contains double quotes: ${plmFile.fileName} $existingPart"
                outbound.uploadError = tr("plm.filename.with.quotes.error", plmFile.name)
                return outbound.build()
            } else {
                PlmFreeCadPart partToBeCloned = PlmFreeCadPart.findByFileIdAndNextVersionIsNull(plmFile.id)
                if (partToBeCloned?.status == PlmFreeCadPartStatus.LOCKED) {
                    log.error "Attempt to update Locked Part (from id): ${plmFile.id} $partToBeCloned"
                    outbound.uploadError = tr("attempt.to.update.locked.part.with.same.id.error", plmFile.id)
                    return outbound.build()
                } else if (partToBeCloned && !plmFreeCadSecurityService.canEditFile(partToBeCloned, u)) {
                    log.error "Attempt to update part you are not supposed to edit (from id): ${plmFile.id} $partToBeCloned"
                    outbound.uploadError = tr("attempt.to.update.a.part.with.same.id.you.are.not.supposed.to.edit.error", plmFile.id)
                    return outbound.build()
                }
                log.info "Upload PlmFile: ${plmFile.name} with id: ${plmFile.id}, already exists: ${existingPart}, part to be cloned ${partToBeCloned}"
                if (!existingPart) {
                    if (!partToBeCloned) {
                        partToBeCloned = new PlmFreeCadPart()
                        partToBeCloned.userCreated = u
                        partToBeCloned.documentCategory = new DocumentCategory(category: DocumentCategoryEnum.OTHER)
                    } else {
                        PlmFreeCadPart oldPart = partToBeCloned.cloneDirectObjectData()
                        partToBeCloned.plmLinks?.each { PlmFreeCadLink lIt ->
                            oldPart.addToPlmLinks(lIt)
                        }

                        oldPart.userUpdated = u
                        if (!oldPart.validate()) {
                            outbound.uploadError = "${oldPart.errors}"
                        }
                        oldPart.save(flush: true, failOnError: true)
                        if (oldPart.hasErrors()) {
                            log.error "oldPart: ${oldPart.errors}"
                        }
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
                    partToBeCloned.originalName = new File(plmFile.fileName).getName()
                    if (partToBeCloned.originalName.toLowerCase().endsWith(".fcstd")) {
                        partToBeCloned.originalName = partToBeCloned.originalName.substring(0, partToBeCloned.originalName.length() - 6)
                    }
                    partToBeCloned.cTimeNs = plmFile.getCTimeNs()
                    partToBeCloned.mTimeNs = plmFile.getUTimeNs()

                    DocumentAccess documentAccess = DocumentAccess.findOrCreateByIsInternalAndIsRestrictedToMyBusinessUnitAndIsRestrictedToMySubsidiaryAndIsRestrictedToMyManagersAndIsRestrictedToEmbeddingObjects(false, false, false, false, true)

                    partToBeCloned.documentAccess = documentAccess
                    if (!partToBeCloned.validate()) {
                        outbound.uploadError = "${partToBeCloned.errors}"
                    }
                    partToBeCloned.save(flush: true, failOnError: true)
                    if (partToBeCloned.hasErrors()) {
                        log.error "partToBeCloned: ${partToBeCloned.errors}"
                    }
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
                    if (!link.validate()) {
                        outbound.uploadError = "${link.errors}"
                    }
                    link.save(flush: true, failOnError: true)
                    if (link.hasErrors()) log.error "link: ${link.errors}"
                }
            } else {
                log.error("No part for ${entry.key} in protobuf !!!")
            }
        }
        outbound.setStatus(FreecadPlm.ServerStatus.OK_FILES)
        return outbound.build()
    }
}
