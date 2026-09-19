package com.hereliesaz.geministrator.azphalt

data class AzphaltStoreSnapshot(
    val repository: AzphaltRepositoryIndex,
    val packages: List<AzphaltPackageSummary>,
    val installed: List<InstalledAzphaltWorkflowPackage>,
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
) {
    private var cachedIndex: AzphaltRepositoryIndex? = null

    suspend fun repositoryIndex(refresh: Boolean = false): AzphaltRepositoryIndex {
        if (!refresh) cachedIndex?.let { return it }
        return repository.index().also { cachedIndex = it }
    }

    suspend fun search(
        query: String = "",
        page: Int = 1,
    ): AzphaltPackageSearchResponse = repository.search(
        query = query,
        kinds = setOf("workflow", "role"),
        page = page,
    )

    suspend fun installed(): List<InstalledAzphaltWorkflowPackage> = installStore.all()

    suspend fun updates(): List<AzphaltPackageUpdate> {
        val local = installStore.all().filter { it.repositoryUrl == repository.repositoryUrl }
        return repository.updates(local.map { AzphaltInstalledPackageRef(it.packageId, it.version) })
    }

    suspend fun revocations(): List<AzphaltRevocation> = repository.revocations()

    suspend fun snapshot(query: String = ""): AzphaltStoreSnapshot {
        val index = repositoryIndex()
        val search = search(query)
        val installed = installed()
        val updates = runCatching { updates() }.getOrDefault(emptyList())
        // Revocations are security state, not optional decoration. Propagate lookup failures instead
        // of rendering a dangerously reassuring empty list.
        val revocations = revocations()
        return AzphaltStoreSnapshot(index, search.packages, installed, updates, revocations)
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
        require(detail.targetApps.isEmpty() || HAIVE_AZPHALT_HOST_ID in detail.targetApps) {
            "Package ${detail.id} does not target $HAIVE_AZPHALT_HOST_ID"
        }
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
        require(manifest.targetApps.isEmpty() || HAIVE_AZPHALT_HOST_ID in manifest.targetApps) {
            "Package ${manifest.id} does not target $HAIVE_AZPHALT_HOST_ID"
        }
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

    private suspend fun requireNotRevoked(
        packageId: String,
        version: String,
        knownRevocations: List<AzphaltRevocation> = revocations(),
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
