# SPDX-License-Identifier: GPL-3.0-only
param([switch]$SmokeTestUi, [string]$ScreenshotPath)
Set-StrictMode -Version Latest
$ErrorActionPreference = 'Stop'
Add-Type -AssemblyName System.Windows.Forms
Add-Type -AssemblyName System.Drawing
[Windows.Forms.Application]::EnableVisualStyles()
. (Join-Path $PSScriptRoot 'Miancak-Keygen.Core.ps1')

try { Assert-MiancakEnvironment }
catch {
    if ($SmokeTestUi) { throw }
    [Windows.Forms.MessageBox]::Show("无法启动。需要已更新的 Windows 10/11、Windows PowerShell 5.1 和 .NET Framework 4.7.2 以上。`r`n`r`n" + $_.Exception.Message, 'MIANCAK', 'OK', 'Error') | Out-Null
    exit 1
}

$form = [Windows.Forms.Form]::new()
$form.Text = 'MIANCAK 签名证书生成器 1.0'
$form.Font = [Drawing.Font]::new('Microsoft YaHei UI', 10)
$form.StartPosition = 'CenterScreen'
$form.ClientSize = [Drawing.Size]::new(760, 670)
$form.MinimumSize = [Drawing.Size]::new(740, 560)
$form.AutoScaleMode = 'Font'
$scroll = [Windows.Forms.Panel]::new()
$scroll.Dock = 'Fill'
$scroll.AutoScroll = $true
$form.Controls.Add($scroll)
$layout = [Windows.Forms.TableLayoutPanel]::new()
$layout.Dock = 'Top'
$layout.Padding = [Windows.Forms.Padding]::new(24)
$layout.ColumnCount = 1
$layout.RowCount = 11
$layout.AutoSize = $true
$layout.AutoSizeMode = 'GrowAndShrink'
for ($i = 0; $i -lt 11; $i++) { $null = $layout.RowStyles.Add([Windows.Forms.RowStyle]::new('AutoSize')) }
$scroll.Controls.Add($layout)

function New-UiLabel([string]$Text, [int]$Bottom = 12) {
    $label = [Windows.Forms.Label]::new()
    $label.Text = $Text
    $label.AutoSize = $true
    $label.MaximumSize = [Drawing.Size]::new(690, 0)
    $label.Margin = [Windows.Forms.Padding]::new(0, 0, 0, $Bottom)
    return $label
}
$title = New-UiLabel '在本机生成 MIANCAK 的长期签名密钥' 10
$title.Font = [Drawing.Font]::new($form.Font.FontFamily, 16, [Drawing.FontStyle]::Bold)
$layout.Controls.Add($title)
$intro = New-UiLabel "无需 Java、无需管理员权限。生成 RSA 3072 位、约 30 年有效期的证书。`r`n私钥使用密码加密；整个生成过程不联网，不上传任何文件。" 18
$layout.Controls.Add($intro)

$folderRow = [Windows.Forms.TableLayoutPanel]::new()
$folderRow.AutoSize = $true
$folderRow.Dock = 'Top'
$folderRow.ColumnCount = 3
$null = $folderRow.ColumnStyles.Add([Windows.Forms.ColumnStyle]::new('AutoSize'))
$null = $folderRow.ColumnStyles.Add([Windows.Forms.ColumnStyle]::new('Percent', 100))
$null = $folderRow.ColumnStyles.Add([Windows.Forms.ColumnStyle]::new('AutoSize'))
$folderLabel = New-UiLabel '保存位置：' 0
$folderLabel.Anchor = 'Left'
$folderText = [Windows.Forms.TextBox]::new()
$folderText.Name = 'ParentDirectory'
$folderText.Text = $PSScriptRoot
$folderText.Dock = 'Fill'
$folderText.ReadOnly = $true
$browse = [Windows.Forms.Button]::new()
$browse.Text = '选择文件夹'
$browse.AutoSize = $true
$folderRow.Controls.Add($folderLabel, 0, 0)
$folderRow.Controls.Add($folderText, 1, 0)
$folderRow.Controls.Add($browse, 2, 0)
$folderRow.Margin = [Windows.Forms.Padding]::new(0, 0, 0, 8)
$layout.Controls.Add($folderRow)
$layout.Controls.Add((New-UiLabel '结果存放在该位置的 MIANCAK-keys 文件夹；文件夹已存在时会拒绝生成。' 16))

$passwordGrid = [Windows.Forms.TableLayoutPanel]::new()
$passwordGrid.AutoSize = $true
$passwordGrid.Dock = 'Top'
$passwordGrid.ColumnCount = 2
$passwordGrid.RowCount = 2
$null = $passwordGrid.ColumnStyles.Add([Windows.Forms.ColumnStyle]::new('AutoSize'))
$null = $passwordGrid.ColumnStyles.Add([Windows.Forms.ColumnStyle]::new('Percent', 100))
$password = [Windows.Forms.TextBox]::new()
$password.Name = 'Password'
$password.UseSystemPasswordChar = $true
$password.MaxLength = 128
$password.Dock = 'Fill'
$confirm = [Windows.Forms.TextBox]::new()
$confirm.Name = 'ConfirmPassword'
$confirm.UseSystemPasswordChar = $true
$confirm.MaxLength = 128
$confirm.Dock = 'Fill'
$passwordGrid.Controls.Add((New-UiLabel '密钥库密码：' 0), 0, 0)
$passwordGrid.Controls.Add($password, 1, 0)
$passwordGrid.Controls.Add((New-UiLabel '再次输入：' 0), 0, 1)
$passwordGrid.Controls.Add($confirm, 1, 1)
$passwordGrid.Margin = [Windows.Forms.Padding]::new(0, 0, 0, 8)
$layout.Controls.Add($passwordGrid)

$options = [Windows.Forms.FlowLayoutPanel]::new()
$options.AutoSize = $true
$options.Dock = 'Top'
$show = [Windows.Forms.CheckBox]::new()
$show.Text = '显示密码'
$show.AutoSize = $true
$random = [Windows.Forms.Button]::new()
$random.Text = '生成 24 位随机密码'
$random.AutoSize = $true
$options.Controls.Add($show)
$options.Controls.Add($random)
$options.Margin = [Windows.Forms.Padding]::new(0, 0, 0, 8)
$layout.Controls.Add($options)
$layout.Controls.Add((New-UiLabel '使用 20–128 位英文字母、数字或符号，不含空格。请先把密码保存到密码管理器。' 12))
$saved = [Windows.Forms.CheckBox]::new()
$saved.Name = 'PasswordSaved'
$saved.Text = '我已单独保存密码（生成器不会把密码写入文件）'
$saved.AutoSize = $true
$saved.Margin = [Windows.Forms.Padding]::new(0, 0, 0, 18)
$layout.Controls.Add($saved)
$layout.Controls.Add((New-UiLabel "生成文件：加密私钥 .p12、公开证书 .pem、证书信息和文件校验值。`r`n用途限定为 org.miancak.keyboard；生成后请另做加密备份。" 18))

$buttons = [Windows.Forms.FlowLayoutPanel]::new()
$buttons.AutoSize = $true
$buttons.Dock = 'Top'
$generate = [Windows.Forms.Button]::new()
$generate.Name = 'Generate'
$generate.Text = '生成密钥与证书'
$generate.AutoSize = $true
$generate.Padding = [Windows.Forms.Padding]::new(8, 4, 8, 4)
$close = [Windows.Forms.Button]::new()
$close.Text = '关闭'
$close.AutoSize = $true
$buttons.Controls.Add($generate)
$buttons.Controls.Add($close)
$layout.Controls.Add($buttons)
$status = New-UiLabel '准备就绪。请只在本机生成一次，后续版本继续使用原密钥。' 0
$status.Name = 'Status'
$status.ForeColor = [Drawing.Color]::FromArgb(55, 70, 80)
$status.Margin = [Windows.Forms.Padding]::new(0, 12, 0, 0)
$layout.Controls.Add($status)

$browse.Add_Click({
    $dialog = [Windows.Forms.FolderBrowserDialog]::new()
    $dialog.Description = '选择本地私有文件夹，结果将放入其 MIANCAK-keys 子文件夹'
    $dialog.SelectedPath = $folderText.Text
    try { if ($dialog.ShowDialog($form) -eq 'OK') { $folderText.Text = $dialog.SelectedPath } }
    finally { $dialog.Dispose() }
})
$show.Add_CheckedChanged({
    $password.UseSystemPasswordChar = -not $show.Checked
    $confirm.UseSystemPasswordChar = -not $show.Checked
})
$random.Add_Click({
    $alphabet = 'ABCDEFGHJKLMNPQRSTUVWXYZabcdefghijkmnopqrstuvwxyz23456789!#%+-_=?'
    $rng = [Security.Cryptography.RandomNumberGenerator]::Create()
    $buffer = New-Object byte[] 1
    $builder = [Text.StringBuilder]::new()
    $limit = 256 - (256 % $alphabet.Length)
    try {
        while ($builder.Length -lt 24) {
            $rng.GetBytes($buffer)
            if ($buffer[0] -lt $limit) { $null = $builder.Append($alphabet[$buffer[0] % $alphabet.Length]) }
        }
        $password.Text = $builder.ToString()
        $confirm.Text = $password.Text
        $saved.Checked = $false
        $status.Text = '已生成随机密码。请先显示并保存到密码管理器，再勾选已保存。'
    }
    finally { $rng.Dispose(); [Array]::Clear($buffer, 0, $buffer.Length); $null = $builder.Clear() }
})
$close.Add_Click({ $form.Close() })
$password.Add_TextChanged({ $saved.Checked = $false })
$confirm.Add_TextChanged({ $saved.Checked = $false })
$generate.Add_Click({
    if (-not $saved.Checked) {
        [Windows.Forms.MessageBox]::Show($form, '请先把密码保存到密码管理器，并勾选已保存。', 'MIANCAK', 'OK', 'Information') | Out-Null
        return
    }
    if ($password.Text -cne $confirm.Text) {
        [Windows.Forms.MessageBox]::Show($form, '两次输入的密码不一致。', 'MIANCAK', 'OK', 'Warning') | Out-Null
        return
    }
    $secret = $null
    try {
        $secret = ConvertTo-SecureString -String $password.Text -AsPlainText -Force
        Assert-MiancakPassword -Password $secret
        $generate.Enabled = $false
        $browse.Enabled = $false
        $random.Enabled = $false
        $close.Enabled = $false
        $status.Text = '正在本机生成并验证，请稍候……'
        $form.UseWaitCursor = $true
        $form.Refresh()
        $result = New-MiancakKeyFiles -ParentDirectory $folderText.Text -Password $secret
        $password.Clear()
        $confirm.Clear()
        $saved.Checked = $false
        $status.Text = '生成成功：' + $result.Directory
        [Windows.Forms.MessageBox]::Show($form, "生成和私钥自检已完成。`r`n`r`n保存位置：" + $result.Directory + "`r`n`r`n请保留原密钥，并制作两份加密备份。密码没有写入任何文件。", 'MIANCAK', 'OK', 'Information') | Out-Null
    }
    catch {
        $status.Text = '未完成生成；已有密钥不会被覆盖。'
        [Windows.Forms.MessageBox]::Show($form, "生成失败。`r`n`r`n" + $_.Exception.Message, 'MIANCAK', 'OK', 'Error') | Out-Null
    }
    finally {
        if ($null -ne $secret) { $secret.Dispose() }
        $generate.Enabled = $true
        $browse.Enabled = $true
        $random.Enabled = $true
        $close.Enabled = $true
        $form.UseWaitCursor = $false
    }
})
$form.Add_FormClosed({ $password.Clear(); $confirm.Clear() })

if ($SmokeTestUi) {
    $form.Show()
    [Windows.Forms.Application]::DoEvents()
    if ($form.Controls.Find('Generate', $true).Count -ne 1 -or $password.Text.Length -ne 0 -or $saved.Checked) {
        throw 'UI initial-state test failed.'
    }
    if ($ScreenshotPath) {
        $image = [Drawing.Bitmap]::new($form.Width, $form.Height)
        try {
            $form.DrawToBitmap($image, [Drawing.Rectangle]::new(0, 0, $image.Width, $image.Height))
            $image.Save($ScreenshotPath, [Drawing.Imaging.ImageFormat]::Png)
        }
        finally { $image.Dispose() }
    }
    $form.Close()
    $form.Dispose()
    Write-Output 'UI_INITIAL_STATE_PASS'
    return
}
try { $null = $form.ShowDialog() }
finally { $form.Dispose() }
