package com.hereliesaz.geministrator.azphalt

import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

data class AzphaltStoreSnapshot(
    val repository: AzphaltRepositoryIndex,
    val packages: List<AzphaltPackageSummary>,
    val installed: List<InstalledAzphaltWorkflowPackage>,
    val installedModels: List<InstalledAzphaltModelPackage>,
    val updates: List<AzphaltPackageUpdate>,
    val revocations: List<AzphaltRevocation>,
)

enum class AzphaltDependencyStatus {
    Satisfied,
    InstallRequired,
    OptionalNotInstalled,
    VersionUnavailable,
    UnsupportedRequiredKind,
    Unavailable,
}

data class AzphaltResolvedDependency(
    val dependency: AzphaltWorkflowDependency,
    val status: AzphaltDependencyStatus,
    val installedVersion: String? = null,
    val selectedVersion: String? = null,
    val packageName: String? = null,
    val packageKind: String? = null,
    val detail: String? = null,
) {
    val blocksInstall: Boolean
        get() = dependency.required && status != AzphaltDependencyStatus.Satisfied
}

data class AzphaltPreparedModelInstall(
    val repositoryUrl: String,
    val detail: AzphaltPackageDetail,
    val version: String,
    val verification: AzphaltPackageVerification,
    val assets: List<AzphaltAssetEntry>,
    val revocationStatusVerified: Boolean = true,
    val localImport: Boolean = false,
)

data class AzphaltPreparedInstall(
    val repositoryUrl: String,
    val detail: AzphaltPackageDetail,
    val version: String,
    val verification: AzphaltPackageVerification,
    val plan: AzphaltWorkflowInstallPlan,
    val dependencies: List<AzphaltResolvedDependency>,
    val revocationStatusVerified: Boolean = true,
    val localImport: Boolean = false,
) {
    val blockingDependencies: List<AzphaltResolvedDependency>
        get() = dependencies.filter(AzphaltResolvedDependency::blocksInstall)
}

/**
 * Stateful Haive host for the public Azphalt Repository API. Package acquisition, integrity/trust,
 * Haive semantic inspection and persistence remain distinct phases so UI confirmation can sit between
 * them without weakening any check.
 */
class AzphaltStoreService(
    private val repository: AzphaltRepositoryClient,
    private val verifier: AzphaltPackageVerifier,
    private val installer: AzphaltWorkflowPackageInstaller,
    private val installStore: AzphaltInstallStore,
    private val modelInstaller: AzphaltModelPackageInstaller? = null,
) {
    private var cachedIndex: AzphaltRepositoryIndex? = null
    private val indexMutex = Mutex()

    suspend fun repositoryIndex(refresh: Boolean = false): AzphaltRepositoryIndex {
        return indexMutex.withLock {
            if (!refresh) cachedIndex?.let { return@withLock it }
            repository.index().also { cachedIndex = it }
        }
    }

    suspend fun search(
        query: String = "",
        page: Int = 1,
    ): AzphaltPackageSearchResponse {
        val orchestration = repository.search(
            query = query,
            kinds = setOf("workflow", "role"),
            page = page,
        )
        val models = repository.search(
            query = query,
            kinds = setOf("asset"),
            mediaDomains = setOf("model"),
            page = page,
        )
        val packages = (orchestration.packages + models.packages)
            .filter { it.kind == "workflow" || it.kind == "role" || it.isModelAssetPackage() }
            .distinctBy(AzphaltPackageSummary::id)
        // total and pages are derived from the deduplicated merged list.
        // Summing the two individual totals would overcount packages that appear in both result sets.
        // Pagination is not fully supported for merged results; pages is set to 1 as a safe default.
        return AzphaltPackageSearchResponse(
            packages = packages,
            total = packages.size,
            page = page,
            pages = 1,
        )
    }

    suspend fun installed(): List<InstalledAzphaltWorkflowPackage> = installStore.all()

    suspend fun installedModels(): List<InstalledAzphaltModelPackage> =
        modelInstaller?.installed().orEmpty()

    fun supportedModelAssetTypes(): Set<String> = modelInstaller?.supportedAssetTypes.orEmpty()

    suspend fun updates(): List<AzphaltPackageUpdate> {
        val local = buildList {
            addAll(
                installStore.all()
                    .filter { it.repositoryUrl == repository.repositoryUrl }
                    .map { AzphaltInstalledPackageRef(it.packageId, it.version) },
            )
            addAll(
                installedModels()
                    .filter { it.repositoryUrl == repository.repositoryUrl }
                    .map { AzphaltInstalledPackageRef(it.packageId, it.version) },
            )
        }.distinctBy(AzphaltInstalledPackageRef::id)
        return repository.updates(local)
    }

    suspend fun revocations(): List<AzphaltRevocation> = repository.revocations()

    suspend fun snapshot(query: String = ""): AzphaltStoreSnapshot {
        val index = repositoryIndex()
        val search = search(query)
        val installed = installed()
        val installedModels = installedModels()
        val updates = runCatching { updates() }.getOrDefault(emptyList())
        // Revocations are security state, not optional decoration. Propagate lookup failures instead
        // of rendering a dangerously reassuring empty list.
        val revocations = revocations()
        return AzphaltStoreSnapshot(index, search.packages, installed, installedModels, updates, revocations)
    }

    suspend fun prepareModelInstall(
        packageId: String,
        requestedVersion: String? = null,
        entitlementToken: String? = null,
    ): AzphaltPreparedModelInstall {
        val host = requireNotNull(modelInstaller) { "Model installation is unavailable on this host" }
        val detail = repository.detail(packageId)
        require(detail.kind == "asset") { "Azphalt model package ${detail.id} must be kind asset" }
        requireTargetsAive(detail.id, detail.targetApps)
        val version = requestedVersion?.let(AzphaltRepositoryClient::requireVersion)
            ?: detail.latest
            ?: detail.version
        requireNotRevoked(detail.id, version)
        val bytes = repository.download(detail.id, version, entitlementToken)
        val verification = verifier.verify(bytes, repositoryIndex().signingKeys)
        val manifest = verification.packageContents.manifest
        require(manifest.id == detail.id) { "Downloaded package id ${manifest.id} does not match repository package ${detail.id}" }
        require(manifest.kind == "asset") { "Downloaded model package ${manifest.id} is kind ${manifest.kind}" }
        require(manifest.version == version) { "Downloaded package version ${manifest.version} does not match requested version $version" }
        requireTargetsAive(manifest.id, manifest.targetApps)
        val compat = azphaltCompatSatisfies(manifest.compat)
        require(compat != null) { "Package ${manifest.id} has invalid Azphalt compat expression ${manifest.compat}" }
        require(compat) { "Package ${manifest.id} requires Azphalt host ${manifest.compat}; Aive implements $HAIVE_AZPHALT_API_VERSION" }

        val assets = manifest.assets.orEmpty().filter(AzphaltAssetEntry::isModelAsset)
        require(assets.isNotEmpty()) { "Package ${manifest.id} contains no model assets" }
        val unsupported = assets.map { it.type }.filterNot { supported ->
            host.supportedAssetTypes.any { it.equals(supported, ignoreCase = true) }
        }.distinct()
        require(unsupported.isEmpty()) {
            "This device cannot install model format${if (unsupported.size == 1) "" else "s"} ${unsupported.joinToString()}"
        }
        assets.forEach { validateModelAsset(it, verification.packageContents) }
        return AzphaltPreparedModelInstall(
            repositoryUrl = repository.repositoryUrl,
            detail = detail,
            version = version,
            verification = verification,
            assets = assets,
        )
    }

    suspend fun prepareLocalModelInstall(bytes: ByteArray): AzphaltPreparedModelInstall {
        val signingKeys = runCatching { repositoryIndex().signingKeys }.getOrDefault(emptyList())
        val verified = verifier.verify(bytes, signingKeys)
        val manifest = verified.packageContents.manifest
        require(manifest.kind == "asset") { "Imported package ${manifest.id} is not a model asset package" }
        requireTargetsAive(manifest.id, manifest.targetApps)

        val revocationResult = runCatching { revocations() }
        revocationResult.getOrNull()?.let { known ->
            requireNotRevoked(manifest.id, manifest.version, known)
        }
        val revocationStatusVerified = revocationResult.isSuccess
        val verification = if (revocationStatusVerified) {
            verified
        } else {
            verified.copy(
                trusted = false,
                trustReason = "${verified.trustReason}; repository revocation status unavailable",
            )
        }

        val compat = azphaltCompatSatisfies(manifest.compat)
        require(compat != null) { "Package ${manifest.id} has invalid Azphalt compat expression ${manifest.compat}" }
        require(compat) { "Package ${manifest.id} requires Azphalt host ${manifest.compat}; Aive implements $HAIVE_AZPHALT_API_VERSION" }
        val assets = manifest.assets.orEmpty().filter(AzphaltAssetEntry::isModelAsset)
        require(assets.isNotEmpty()) { "Package ${manifest.id} contains no model assets" }
        val host = requireNotNull(modelInstaller) { "Model installation is unavailable on this host" }
        val unsupported = assets.map(AzphaltAssetEntry::type).filterNot { type ->
            host.supportedAssetTypes.any { it.equals(type, ignoreCase = true) }
        }.distinct()
        require(unsupported.isEmpty()) { "This device cannot install model formats ${unsupported.joinToString()}" }
        assets.forEach { validateModelAsset(it, verification.packageContents) }

        val detail = AzphaltPackageDetail(
            id = manifest.id,
            name = manifest.name,
            author = manifest.author,
            description = manifest.description,
            version = manifest.version,
            latest = manifest.version,
            kind = manifest.kind,
            targetApps = manifest.targetApps,
            manifest = manifest,
            versions = listOf(AzphaltPackageVersion(version = manifest.version, size = bytes.size.toLong())),
        )
        return AzphaltPreparedModelInstall(
            repositoryUrl = repository.repositoryUrl,
            detail = detail,
            version = manifest.version,
            verification = verification,
            assets = assets,
            revocationStatusVerified = revocationStatusVerified,
            localImport = true,
        )
    }

    suspend fun installModel(
        prepared: AzphaltPreparedModelInstall,
        nowEpochMillis: Long,
        allowUntrustedSigner: Boolean = false,
        allowPublisherChange: Boolean = false,
    ): InstalledAzphaltModelPackage {
        val host = requireNotNull(modelInstaller) { "Model installation is unavailable on this host" }
        if (prepared.revocationStatusVerified) {
            requireNotRevoked(prepared.detail.id, prepared.version)
        } else {
            require(prepared.localImport && allowUntrustedSigner) {
                "Repository revocation status is unavailable; explicit offline/untrusted approval is required"
            }
        }
        val verification = prepared.verification
        require(!verification.publisherChanged || allowPublisherChange) {
            "Publisher key changed for ${prepared.detail.id}; explicit publisher-change approval is required"
        }
        require(
            !verification.packageContents.signed ||
                verification.trusted ||
                allowUntrustedSigner,
        ) { "Signed package publisher is not trusted: ${verification.trustReason}" }
        val installed = host.install(prepared, nowEpochMillis)
        if (verification.packageContents.signerPublicKey != null &&
            (verification.pinnedPublisherKey == null || allowPublisherChange)
        ) {
            verifier.approvePublisher(verification)
        }
        return installed
    }

    suspend fun removeModel(packageId: String) {
        requireNotNull(modelInstaller) { "Model installation is unavailable on this host" }.remove(packageId)
    }

    suspend fun prepareInstall(
        packageId: String,
        requestedVersion: String? = null,
        entitlementToken: String? = null,
    ): AzphaltPreparedInstall {
        val detail = repository.detail(packageId)
        require(detail.kind == "workflow" || detail.kind == "role") {
            "Haive Store installs workflow and role packages; ${detail.id} is kind ${detail.kind}"
        }
        requireTargetsAive(detail.id, detail.targetApps)
        val version = requestedVersion?.let(AzphaltRepositoryClient::requireVersion)
            ?: detail.latest
            ?: detail.version
        requireNotRevoked(detail.id, version)
        val bytes = repository.download(detail.id, version, entitlementToken)
        val verification = verifier.verify(bytes, repositoryIndex().signingKeys)
        val manifest = verification.packageContents.manifest
        require(manifest.id == detail.id) {
            "Downloaded package id ${manifest.id} does not match repository package ${detail.id}"
        }
        require(manifest.kind == detail.kind) {
            "Downloaded package kind ${manifest.kind} does not match repository package kind ${detail.kind}"
        }
        require(manifest.version == version) {
            "Downloaded package version ${manifest.version} does not match requested version $version"
        }
        val compat = azphaltCompatSatisfies(manifest.compat)
        require(compat != null) { "Package ${manifest.id} has invalid Azphalt compat expression ${manifest.compat}" }
        require(compat) {
            "Package ${manifest.id} requires Azphalt host ${manifest.compat}; Haive implements $HAIVE_AZPHALT_API_VERSION"
        }
        val plan = installer.inspect(verification)
        val dependencies = resolveDependencies(plan.dependencies)
        return AzphaltPreparedInstall(
            repositoryUrl = repository.repositoryUrl,
            detail = detail,
            version = version,
            verification = verification,
            plan = plan,
            dependencies = dependencies,
        )
    }

    /**
     * Prepare a package supplied by the host (for example Android ACTION_VIEW/ACTION_SEND) without
     * bypassing Store verification. Repository signing keys are used when reachable; an offline
     * import remains cryptographically verified but is presented as untrusted for explicit approval.
     */
    suspend fun prepareLocalInstall(bytes: ByteArray): AzphaltPreparedInstall {
        val signingKeys = runCatching { repositoryIndex().signingKeys }.getOrDefault(emptyList())
        val verified = verifier.verify(bytes, signingKeys)
        val manifest = verified.packageContents.manifest
        val revocationResult = runCatching { revocations() }
        revocationResult.getOrNull()?.let { known ->
            requireNotRevoked(manifest.id, manifest.version, known)
        }
        val revocationStatusVerified = revocationResult.isSuccess
        val verification = if (revocationStatusVerified) {
            verified
        } else {
            verified.copy(
                trusted = false,
                trustReason = "${verified.trustReason}; repository revocation status unavailable",
            )
        }
        require(manifest.kind == "workflow" || manifest.kind == "role") {
            "Haive Store installs workflow and role packages; ${manifest.id} is kind ${manifest.kind}"
        }
        requireTargetsAive(manifest.id, manifest.targetApps)
        val compat = azphaltCompatSatisfies(manifest.compat)
        require(compat != null) { "Package ${manifest.id} has invalid Azphalt compat expression ${manifest.compat}" }
        require(compat) {
            "Package ${manifest.id} requires Azphalt host ${manifest.compat}; Haive implements $HAIVE_AZPHALT_API_VERSION"
        }
        val plan = installer.inspect(verification)
        val dependencies = resolveDependencies(plan.dependencies)
        val detail = AzphaltPackageDetail(
            id = manifest.id,
            name = manifest.name,
            author = manifest.author,
            description = manifest.description,
            version = manifest.version,
            latest = manifest.version,
            kind = manifest.kind,
            targetApps = manifest.targetApps,
            manifest = manifest,
            versions = listOf(
                AzphaltPackageVersion(
                    version = manifest.version,
                    size = bytes.size.toLong(),
                ),
            ),
        )
        return AzphaltPreparedInstall(
            repositoryUrl = repository.repositoryUrl,
            detail = detail,
            version = manifest.version,
            verification = verification,
            plan = plan,
            dependencies = dependencies,
            revocationStatusVerified = revocationStatusVerified,
            localImport = true,
        )
    }

    suspend fun install(
        prepared: AzphaltPreparedInstall,
        approvedHostPermissions: Set<String>,
        nowEpochMillis: Long,
        allowUntrustedSigner: Boolean = false,
        allowPublisherChange: Boolean = false,
    ): InstalledAzphaltWorkflowPackage {
        if (prepared.revocationStatusVerified) {
            // Re-check at the mutation boundary so a revocation published after preparation wins.
            requireNotRevoked(prepared.plan.packageId, prepared.version)
        } else {
            require(prepared.localImport && allowUntrustedSigner) {
                "Repository revocation status is unavailable; explicit offline/untrusted approval is required"
            }
        }
        val currentDependencies = resolveDependencies(prepared.plan.dependencies)
        val blockers = currentDependencies.filter(AzphaltResolvedDependency::blocksInstall)
        require(blockers.isEmpty()) {
            "Required Azphalt dependencies are unresolved: " + blockers.joinToString { dependency ->
                val range = dependency.dependency.version?.let { " $it" }.orEmpty()
                "${dependency.dependency.id}$range (${dependency.status.name})"
            }
        }
        return installer.install(
            plan = prepared.plan,
            repositoryUrl = prepared.repositoryUrl,
            approvedHostPermissions = approvedHostPermissions,
            nowEpochMillis = nowEpochMillis,
            allowUntrustedSigner = allowUntrustedSigner,
            allowPublisherChange = allowPublisherChange,
        )
    }

    suspend fun resolveDependencies(
        dependencies: List<AzphaltWorkflowDependency>,
    ): List<AzphaltResolvedDependency> {
        if (dependencies.isEmpty()) return emptyList()
        val installedById = installStore.all().associateBy(InstalledAzphaltWorkflowPackage::packageId)
        return dependencies.map { dependency ->
            val local = installedById[dependency.id]
            if (local != null && dependencyVersionSatisfies(local.version, dependency.version)) {
                return@map AzphaltResolvedDependency(
                    dependency = dependency,
                    status = AzphaltDependencyStatus.Satisfied,
                    installedVersion = local.version,
                    selectedVersion = local.version,
                )
            }

            val detail = runCatching { repository.detail(dependency.id) }.getOrElse { failure ->
                return@map AzphaltResolvedDependency(
                    dependency = dependency,
                    status = if (dependency.required) AzphaltDependencyStatus.Unavailable else AzphaltDependencyStatus.OptionalNotInstalled,
                    installedVersion = local?.version,
                    detail = failure.message,
                )
            }
            val candidate = selectDependencyVersion(detail, dependency.version)
            if (candidate == null) {
                return@map AzphaltResolvedDependency(
                    dependency = dependency,
                    status = if (dependency.required) AzphaltDependencyStatus.VersionUnavailable else AzphaltDependencyStatus.OptionalNotInstalled,
                    installedVersion = local?.version,
                    packageName = detail.name,
                    packageKind = detail.kind,
                    detail = "No repository version satisfies ${dependency.version ?: "the requested version"}",
                )
            }
            if (detail.kind != "workflow") {
                return@map AzphaltResolvedDependency(
                    dependency = dependency,
                    status = if (dependency.required) AzphaltDependencyStatus.UnsupportedRequiredKind else AzphaltDependencyStatus.OptionalNotInstalled,
                    installedVersion = local?.version,
                    selectedVersion = candidate,
                    packageName = detail.name,
                    packageKind = detail.kind,
                    detail = "Dependency retains its own ${detail.kind} host lifecycle and cannot be folded into a workflow install",
                )
            }
            AzphaltResolvedDependency(
                dependency = dependency,
                status = if (dependency.required) AzphaltDependencyStatus.InstallRequired else AzphaltDependencyStatus.OptionalNotInstalled,
                installedVersion = local?.version,
                selectedVersion = candidate,
                packageName = detail.name,
                packageKind = detail.kind,
            )
        }
    }

    private fun requireTargetsAive(packageId: String, targetApps: List<String>) {
        val accepted = setOf(HAIVE_AZPHALT_HOST_ID, LEGACY_HAIVE_AZPHALT_HOST_ID)
        require(targetApps.isEmpty() || targetApps.any(accepted::contains)) {
            "Package $packageId does not target The Aive"
        }
    }

    private fun validateModelAsset(asset: AzphaltAssetEntry, pkg: VerifiedAzphaltPackage) {
        require(asset.type.isNotBlank()) { "Model asset type must not be blank" }
        if (asset.files.isNotEmpty()) {
            asset.files.forEach { member ->
                val bundled = member.path?.takeIf(String::isNotBlank)
                val remote = member.remoteUrl?.takeIf(String::isNotBlank)
                require((bundled == null) != (remote == null)) {
                    "Model member ${member.name} must declare exactly one of path or remoteUrl"
                }
                if (bundled != null) {
                    require(bundled in pkg.payload) { "Bundled model member $bundled is missing" }
                } else {
                    require(remote!!.startsWith("https://", ignoreCase = true)) { "Remote model member must use HTTPS" }
                    requireSha256(member.checksum, member.name)
                }
            }
        } else {
            val bundled = asset.path.takeIf(String::isNotBlank)
            val remote = asset.remoteUrl?.takeIf(String::isNotBlank)
            require((bundled == null) != (remote == null)) {
                "Model asset ${asset.role ?: asset.type} must declare exactly one of path or remoteUrl"
            }
            if (bundled != null) {
                require(bundled in pkg.payload) { "Bundled model asset $bundled is missing" }
            } else {
                require(remote!!.startsWith("https://", ignoreCase = true)) { "Remote model asset must use HTTPS" }
                requireSha256(asset.checksum, asset.role ?: asset.type)
            }
        }
    }

    private fun requireSha256(value: String?, label: String) {
        require(value?.lowercase()?.matches(Regex("sha256-[0-9a-f]{64}")) == true) {
            "Remote model $label requires a sha256-<hex> checksum"
        }
    }

    private suspend fun requireNotRevoked(packageId: String, version: String) {
        requireNotRevoked(packageId, version, revocations())
    }

    private fun requireNotRevoked(
        packageId: String,
        version: String,
        knownRevocations: List<AzphaltRevocation>,
    ) {
        val revocation = knownRevocations.firstOrNull { it.id == packageId && it.version == version }
        require(revocation == null) {
            buildString {
                append("Azphalt package ")
                append(packageId)
                append('@')
                append(version)
                append(" is revoked")
                revocation?.reason?.takeIf(String::isNotBlank)?.let { append(": ").append(it) }
            }
        }
    }

    private fun selectDependencyVersion(detail: AzphaltPackageDetail, requirement: String?): String? {
        val versions = buildList {
            detail.latest?.let(::add)
            add(detail.version)
            detail.versions.filterNot(AzphaltPackageVersion::yanked).forEach { add(it.version) }
        }.distinct()
        return versions
            .mapNotNull { version -> ParsedSemVer.parse(version)?.let { parsed -> version to parsed } }
            .filter { (version, _) -> dependencyVersionSatisfies(version, requirement) }
            .maxByOrNull { it.second }
            ?.first
    }
}

private data class ParsedSemVer(
    val major: Int,
    val minor: Int,
    val patch: Int,
) : Comparable<ParsedSemVer> {
    override fun compareTo(other: ParsedSemVer): Int =
        compareValuesBy(this, other, ParsedSemVer::major, ParsedSemVer::minor, ParsedSemVer::patch)

    companion object {
        fun parse(raw: String): ParsedSemVer? {
            val numeric = raw.trim().substringBefore('-').substringBefore('+')
            val parts = numeric.split('.')
            if (parts.isEmpty() || parts.size > 3) return null
            val values = parts.map { it.toIntOrNull() ?: return null }
            return ParsedSemVer(values[0], values.getOrElse(1) { 0 }, values.getOrElse(2) { 0 })
        }
    }
}

/** Small dependency matcher: exact/bare semver plus single comparator requirements used by Azphalt workflows. */
internal fun dependencyVersionSatisfies(version: String, requirement: String?): Boolean {
    if (requirement.isNullOrBlank()) return true
    val actual = ParsedSemVer.parse(version) ?: return false
    val expression = requirement.trim()
    val match = Regex("^(>=|>|<=|<|=)?\\s*([0-9]+(?:\\.[0-9]+){0,2})$").matchEntire(expression) ?: return false
    val required = ParsedSemVer.parse(match.groupValues[2]) ?: return false
    val comparison = actual.compareTo(required)
    return when (match.groupValues[1].ifEmpty { "=" }) {
        ">=" -> comparison >= 0
        ">" -> comparison > 0
        "<=" -> comparison <= 0
        "<" -> comparison < 0
        "=" -> comparison == 0
        else -> false
    }
}
