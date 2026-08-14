# Creates the development login accounts and links them to salesperson rows.
#
# Supabase Auth is email-based, so a rep's username is mapped to a synthetic
# address: <code>@esales.local. The Android client performs the same mapping,
# which is why reps can keep typing "nvbh01" as they always have.
#
# Reads credentials from local.properties (gitignored). Requires the project's
# secret key, which is why this is a script you run rather than something the
# app ever does.
#
# Usage:  pwsh -File scripts/seed_auth_users.ps1

$ErrorActionPreference = 'Stop'

$root = Split-Path -Parent $PSScriptRoot
$propsFile = Join-Path $root 'local.properties'
if (-not (Test-Path $propsFile)) { throw "local.properties not found at $propsFile" }

$props = @{}
Get-Content $propsFile | ForEach-Object {
    if ($_ -match '^\s*([^#][^=]*?)\s*=\s*(.*)$') { $props[$Matches[1]] = $Matches[2] }
}

$ref = $props['supabase.projectRef']
if (-not $ref) { throw 'supabase.projectRef missing from local.properties' }
$baseUrl = "https://$ref.supabase.co"

# The privileged key is not stored in local.properties on purpose; pull it live.
#
# Note: GoTrue's admin endpoints on this project reject the newer
# `sb_secret_...` key with 403 and only accept the legacy service_role JWT, so
# that is what we ask for here. PostgREST accepts either.
$keys = supabase projects api-keys --project-ref $ref --output json | ConvertFrom-Json
$secret = ($keys | Where-Object { $_.name -eq 'service_role' }).api_key
if (-not $secret) { throw 'Could not read the project service_role key' }

$headers = @{
    apikey          = $secret
    Authorization   = "Bearer $secret"
    'Content-Type'  = 'application/json'
    'User-Agent'    = 'esales-sfa-seed/1.0'
}

# code -> password. Development only.
$accounts = @(
    @{ code = 'nvbh01'; password = 'Esales@12345' },
    @{ code = 'nvbh02'; password = 'Esales@12345' }
)

# One listing for all accounts; the dev project has a handful of users.
$allUsers = (Invoke-RestMethod -Method Get -Headers $headers `
    -Uri "$baseUrl/auth/v1/admin/users?page=1&per_page=200").users

foreach ($acct in $accounts) {
    $email = "$($acct.code)@esales.local"

    $user = $allUsers | Where-Object { $_.email -eq $email } | Select-Object -First 1

    if ($user) {
        Write-Host "exists   $email  ($($user.id))"
    } else {
        $body = @{
            email         = $email
            password      = $acct.password
            email_confirm = $true
            user_metadata = @{ salesperson_code = $acct.code }
        } | ConvertTo-Json -Depth 5

        $user = Invoke-RestMethod -Method Post -Headers $headers -Body $body `
            -Uri "$baseUrl/auth/v1/admin/users"
        Write-Host "created  $email  ($($user.id))"
    }

    # Link the auth user to the salesperson row. PATCH via PostgREST using the
    # secret key, which bypasses RLS.
    $patch = @{ user_id = $user.id } | ConvertTo-Json
    Invoke-RestMethod -Method Patch -Body $patch `
        -Headers ($headers + @{ Prefer = 'return=minimal' }) `
        -Uri "$baseUrl/rest/v1/salesperson?code=eq.$($acct.code)" | Out-Null
    Write-Host "linked   salesperson.code=$($acct.code)"
}

Write-Host ''
Write-Host 'Done. Sign in with username nvbh01 / password Esales@12345'
