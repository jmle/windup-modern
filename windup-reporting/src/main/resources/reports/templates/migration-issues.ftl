<!DOCTYPE html>
<html lang="en">
<head>
    <meta charset="UTF-8">
    <meta name="viewport" content="width=device-width, initial-scale=1.0">
    <title>${applicationName!""} - Migration Issues</title>
    <style>
        :root {
            --pf-color-primary: #06c;
            --pf-color-bg: #f0f0f0;
            --pf-color-surface: #fff;
            --pf-color-text: #151515;
            --pf-color-text-secondary: #6a6e73;
            --pf-color-border: #d2d2d2;
            --pf-color-danger: #c9190b;
            --pf-color-warning: #f0ab00;
        }
        * { margin: 0; padding: 0; box-sizing: border-box; }
        body {
            font-family: 'RedHatText', 'Helvetica Neue', Arial, sans-serif;
            background-color: var(--pf-color-bg);
            color: var(--pf-color-text);
            line-height: 1.5;
        }
        .header {
            background-color: #151515;
            color: #fff;
            padding: 16px 32px;
        }
        .header h1 { font-size: 20px; font-weight: 400; }
        .container { max-width: 1200px; margin: 0 auto; padding: 24px; }
        .page-title { font-size: 24px; margin-bottom: 24px; }
        .severity-section { margin-bottom: 32px; }
        .severity-section h2 {
            font-size: 18px;
            border-bottom: 2px solid var(--pf-color-primary);
            padding-bottom: 8px;
            margin-bottom: 16px;
        }
        table {
            width: 100%;
            border-collapse: collapse;
            background: var(--pf-color-surface);
            border: 1px solid var(--pf-color-border);
            margin-bottom: 16px;
        }
        th, td { padding: 10px 14px; text-align: left; border-bottom: 1px solid var(--pf-color-border); }
        th { background-color: #f0f0f0; font-weight: 600; font-size: 13px; }
        td { font-size: 13px; }
        .empty-message {
            color: var(--pf-color-text-secondary);
            font-style: italic;
            padding: 16px;
        }
        .nav-link {
            display: inline-block;
            margin-bottom: 16px;
            color: var(--pf-color-primary);
            text-decoration: none;
        }
        .nav-link:hover { text-decoration: underline; }
        .footer {
            text-align: center;
            padding: 24px;
            color: var(--pf-color-text-secondary);
            font-size: 13px;
        }
    </style>
</head>
<body>
    <div class="header">
        <h1>Windup Analysis Report</h1>
    </div>
    <div class="container">
        <a class="nav-link" href="index.html">Back to Summary</a>
        <h1 class="page-title">${applicationName!""} - Migration Issues</h1>

        <#list severities as severity>
        <div class="severity-section">
            <h2>${severity}</h2>

            <#assign hasHints = (hintsBySeverity[severity])?? && (hintsBySeverity[severity])?has_content>
            <#assign hasClassifications = (classificationsBySeverity[severity])?? && (classificationsBySeverity[severity])?has_content>

            <#if hasHints>
            <h3 style="font-size:15px; margin-bottom:8px;">Hints</h3>
            <table>
                <thead>
                    <tr>
                        <th>Rule ID</th>
                        <th>Title</th>
                        <th>File</th>
                        <th>Line</th>
                        <th>Effort</th>
                    </tr>
                </thead>
                <tbody>
                    <#list hintsBySeverity[severity] as hint>
                    <tr>
                        <td>${hint.ruleId!""}</td>
                        <td>${hint.title!""}</td>
                        <td>${(hint.sourceFile.fileName)!""}</td>
                        <td>${hint.lineNumber}</td>
                        <td>${(hint.effort.storyPoints)!0}</td>
                    </tr>
                    </#list>
                </tbody>
            </table>
            </#if>

            <#if hasClassifications>
            <h3 style="font-size:15px; margin-bottom:8px;">Classifications</h3>
            <table>
                <thead>
                    <tr>
                        <th>Rule ID</th>
                        <th>Title</th>
                        <th>Description</th>
                        <th>File</th>
                        <th>Effort</th>
                    </tr>
                </thead>
                <tbody>
                    <#list classificationsBySeverity[severity] as classification>
                    <tr>
                        <td>${classification.ruleId!""}</td>
                        <td>${classification.title!""}</td>
                        <td>${classification.description!""}</td>
                        <td>${(classification.sourceFile.fileName)!""}</td>
                        <td>${(classification.effort.storyPoints)!0}</td>
                    </tr>
                    </#list>
                </tbody>
            </table>
            </#if>

            <#if !hasHints && !hasClassifications>
            <p class="empty-message">No issues found at this severity level.</p>
            </#if>
        </div>
        </#list>
    </div>
    <div class="footer">
        Generated by Windup
    </div>
</body>
</html>
