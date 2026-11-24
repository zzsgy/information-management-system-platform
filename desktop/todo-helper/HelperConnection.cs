using System;
using System.IO;
using System.Linq;
using System.Net.Http;
using System.Net.Security;
using System.Security.Cryptography;
using System.Security.Cryptography.X509Certificates;

/// <summary>配对、提醒和更新共用的 HTTPS 客户端。额外信任的公司 CA 仅适用于正式系统入口。</summary>
internal static class HelperConnection
{
    private const string CompanyOrigin = "https://192.168.20.222:5200";
    private static readonly X509Certificate2 CompanyRoot = LoadCompanyRoot();

    internal static HttpClient CreateClient()
    {
        var handler = new HttpClientHandler {
            AllowAutoRedirect = false, UseCookies = false,
            ServerCertificateCustomValidationCallback = ValidateServer
        };
        return new HttpClient(handler) { Timeout = TimeSpan.FromSeconds(7), MaxResponseContentBufferSize = HelperUpdater.MaxPackageBytes };
    }

    /// <summary>系统信任优先。只有公司入口缺少受信任的签发者时，才使用随助手发布的公开根证书重新验证。</summary>
    private static bool ValidateServer(HttpRequestMessage request, X509Certificate2 certificate, X509Chain systemChain, SslPolicyErrors errors)
    {
        if (errors == SslPolicyErrors.None) return true;
        if (errors != SslPolicyErrors.RemoteCertificateChainErrors || systemChain == null ||
            request.RequestUri.GetLeftPart(UriPartial.Authority) != CompanyOrigin) return false;
        const X509ChainStatusFlags missingIssuer = X509ChainStatusFlags.UntrustedRoot | X509ChainStatusFlags.PartialChain;
        if (systemChain.ChainStatus.Any(status => (status.Status & ~missingIssuer) != 0)) return false;

        using (var companyChain = new X509Chain())
        {
            var policy = companyChain.ChainPolicy;
            policy.ExtraStore.Add(CompanyRoot);
            foreach (X509ChainElement element in systemChain.ChainElements)
                policy.ExtraStore.Add(element.Certificate);
            policy.ApplicationPolicy.Add(new Oid("1.3.6.1.5.5.7.3.1")); // TLS 服务器身份验证
            policy.RevocationMode = systemChain.ChainPolicy.RevocationMode;
            policy.RevocationFlag = systemChain.ChainPolicy.RevocationFlag;
            // .NET Framework 的 ExtraStore 只提供证书，允许未知 CA 后还须核对签名链确实终止于内置根。
            policy.VerificationFlags = X509VerificationFlags.AllowUnknownCertificateAuthority;
            if (!companyChain.Build(certificate)) return false;
            var root = companyChain.ChainElements[companyChain.ChainElements.Count - 1].Certificate;
            return root.RawData.SequenceEqual(CompanyRoot.RawData);
        }
    }

    /// <summary>读取构建时嵌入 EXE 的公开证书，整个流程不写 Windows 证书库。</summary>
    private static X509Certificate2 LoadCompanyRoot()
    {
        using (var resource = typeof(HelperConnection).Assembly.GetManifestResourceStream("IMS-LAN-CA.cer"))
        using (var reader = new BinaryReader(resource))
            return new X509Certificate2(reader.ReadBytes((int)resource.Length));
    }
}
