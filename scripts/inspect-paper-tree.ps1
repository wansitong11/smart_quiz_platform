# 看一眼某份试卷切题结果的层级树：各节点分值 + 每道题的分值/题型，用来核对卷面总分
param(
  [int]$PaperId = 4,
  [int]$Chars = 34,
  [string]$PgContainer = 'my_pgvector',
  [string]$PgDatabase = 'smart-quiz',
  [string]$PgUser = 'postgres',
  [switch]$Full
)

$ErrorActionPreference = 'Continue'

$sql = "SELECT paper_json FROM practice_sessions WHERE resume_id=$PaperId AND source_type='PAPER' ORDER BY id DESC LIMIT 1;"
$raw = (& docker exec $PgContainer psql -U $PgUser -d $PgDatabase -t -A -c $sql 2>&1 | Out-String).Trim()
if (-not $raw) { Write-Error "没有找到试卷 $PaperId 的切题结果"; exit 1 }
$tree = $raw | ConvertFrom-Json

$script:total = 0
$script:count = 0
$script:typeSum = @{}

function Show-Node {
  param($Node, [int]$Depth)
  $pad = '  ' * $Depth
  if ($Node.type -eq 'QUESTION' -or $null -ne $Node.question) {
    $q = $Node.question
    $score = if ($null -ne $q.fullScore) { $q.fullScore } elseif ($null -ne $Node.meta.fullScore) { $Node.meta.fullScore } else { 0 }
    $script:total += [int]$score
    $script:count++
    $t = [string]$q.type
    if (-not $script:typeSum.ContainsKey($t)) { $script:typeSum[$t] = @{ n = 0; s = 0 } }
    $script:typeSum[$t].n++
    $script:typeSum[$t].s += [int]$score
    $stem = ([string]$q.question -replace '\s+', ' ')
    if (-not $Full -and $stem.Length -gt $Chars) { $stem = $stem.Substring(0, $Chars) + '…' }
    "{0}Q{1,-3} {2,-14} {3,3}分  {4}" -f $pad, $q.index, $t, $score, $stem
  } else {
    $metaScore = if ($null -ne $Node.meta.fullScore) { $Node.meta.fullScore } else { '-' }
    $metaCount = if ($null -ne $Node.meta.questionCount) { $Node.meta.questionCount } else { '-' }
    "{0}[{1}] {2} (分值={3} 题数={4})" -f $pad, $Node.type, $Node.title, $metaScore, $metaCount
  }
  foreach ($c in $Node.children) { Show-Node -Node $c -Depth ($Depth + 1) }
}

Show-Node -Node $tree.root -Depth 0
''
"题目总数 = $($script:count)"
"卷面总分 = $($script:total)"
"按题型: " + (($script:typeSum.GetEnumerator() | Sort-Object Name | ForEach-Object { "$($_.Key)=$($_.Value.n)道/$($_.Value.s)分" }) -join '，')
