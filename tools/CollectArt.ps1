$ErrorActionPreference = 'Stop'
$root = Split-Path $PSScriptRoot -Parent
$target = Join-Path $root 'app/src/main/assets/art'
$items = @()
foreach ($term in @('Monet','Cezanne','landscape','still life')) {
    $query = [uri]::EscapeDataString($term)
    $response = Invoke-RestMethod "https://api.artic.edu/api/v1/artworks/search?q=$query&limit=100&fields=id,title,artist_display,date_display,image_id,is_public_domain,artwork_type_title"
    $chosen = $response.data | Where-Object { $_.is_public_domain -and $_.image_id -and $_.artwork_type_title -eq 'Painting' -and $_.id -notin $items.id } | Select-Object -First 6
    foreach ($art in $chosen) {
        $file = Join-Path $target "$($art.id).jpg"
        if (!(Test-Path $file)) { Invoke-WebRequest -UseBasicParsing "https://www.artic.edu/iiif/2/$($art.image_id)/full/1686,/0/default.jpg" -OutFile $file }
        $items += [pscustomobject]@{ id=$art.id; title=$art.title; artist=$art.artist_display; date=$art.date_display; file="art/$($art.id).jpg"; source="https://www.artic.edu/artworks/$($art.id)"; license='Public domain / Art Institute of Chicago' }
        Write-Output $art.title
    }
}
$items | ConvertTo-Json -Depth 4 | Set-Content -Encoding UTF8 (Join-Path $target 'catalog.json')
Write-Output "Downloaded $($items.Count) paintings."
