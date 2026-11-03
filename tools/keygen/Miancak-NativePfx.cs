// SPDX-License-Identifier: GPL-3.0-only
using System;
using System.ComponentModel;
using System.Runtime.InteropServices;
using System.Security;
using System.Security.Cryptography.X509Certificates;

namespace Miancak {
    // Thin binding to Windows' documented PKCS12 exporter. No custom cryptography.
    public static class NativePfx {
        [StructLayout(LayoutKind.Sequential)]
        private struct Blob { public uint Size; public IntPtr Data; }
        [StructLayout(LayoutKind.Sequential, CharSet = CharSet.Unicode)]
        private struct Pbes2 {
            public uint Size;
            public IntPtr Descriptor;
            [MarshalAs(UnmanagedType.LPWStr)] public string Algorithm;
        }
        [DllImport("crypt32.dll", SetLastError = true)]
        private static extern IntPtr CertOpenStore(IntPtr provider, uint encoding, IntPtr cryptProvider, uint flags, IntPtr parameter);
        [DllImport("crypt32.dll", SetLastError = true)]
        [return: MarshalAs(UnmanagedType.Bool)]
        private static extern bool CertAddCertificateLinkToStore(IntPtr store, IntPtr context, uint disposition, out IntPtr linkedContext);
        [DllImport("crypt32.dll", SetLastError = true)]
        [return: MarshalAs(UnmanagedType.Bool)]
        private static extern bool PFXExportCertStoreEx(IntPtr store, ref Blob blob, IntPtr password, ref Pbes2 parameters, uint flags);
        [DllImport("crypt32.dll")]
        [return: MarshalAs(UnmanagedType.Bool)]
        private static extern bool CertFreeCertificateContext(IntPtr context);
        [DllImport("crypt32.dll")]
        [return: MarshalAs(UnmanagedType.Bool)]
        private static extern bool CertCloseStore(IntPtr store, uint flags);

        public static byte[] Export(X509Certificate2 certificate, SecureString password) {
            if (certificate == null || !certificate.HasPrivateKey) throw new ArgumentException("A certificate with a private key is required.");
            if (password == null || password.Length < 20) throw new ArgumentException("A strong password is required.");
            IntPtr store = IntPtr.Zero, linked = IntPtr.Zero, passwordPointer = IntPtr.Zero;
            Blob blob = new Blob();
            try {
                store = CertOpenStore(new IntPtr(2), 0, IntPtr.Zero, 0, IntPtr.Zero); // CERT_STORE_PROV_MEMORY
                if (store == IntPtr.Zero) throw new Win32Exception(Marshal.GetLastWin32Error(), "Cannot create in-memory certificate store.");
                if (!CertAddCertificateLinkToStore(store, certificate.Handle, 4, out linked)) // CERT_STORE_ADD_ALWAYS
                    throw new Win32Exception(Marshal.GetLastWin32Error(), "Cannot link the in-memory signing certificate.");
                Pbes2 parameters = new Pbes2();
                parameters.Size = (uint)Marshal.SizeOf(typeof(Pbes2));
                parameters.Algorithm = "AES256-SHA256";
                passwordPointer = Marshal.SecureStringToGlobalAllocUnicode(password);
                // Require a private key and exportability, retain the alias, and select PBES2 explicitly.
                const uint flags = 0x0004 | 0x0001 | 0x0002 | 0x0010 | 0x0080;
                if (!PFXExportCertStoreEx(store, ref blob, passwordPointer, ref parameters, flags))
                    throw new Win32Exception(Marshal.GetLastWin32Error(), "Windows could not export an AES256-SHA256 private-key bundle.");
                if (blob.Size == 0 || blob.Size > 1024 * 1024) throw new InvalidOperationException("Unexpected PKCS12 size.");
                blob.Data = Marshal.AllocHGlobal((int)blob.Size);
                if (!PFXExportCertStoreEx(store, ref blob, passwordPointer, ref parameters, flags))
                    throw new Win32Exception(Marshal.GetLastWin32Error(), "Windows could not finish the encrypted private-key export.");
                byte[] result = new byte[blob.Size];
                Marshal.Copy(blob.Data, result, 0, result.Length);
                return result;
            } finally {
                if (blob.Data != IntPtr.Zero) {
                    for (int i = 0; i < blob.Size; i++) Marshal.WriteByte(blob.Data, i, 0);
                    Marshal.FreeHGlobal(blob.Data);
                }
                if (passwordPointer != IntPtr.Zero) Marshal.ZeroFreeGlobalAllocUnicode(passwordPointer);
                if (linked != IntPtr.Zero) CertFreeCertificateContext(linked);
                if (store != IntPtr.Zero) CertCloseStore(store, 0);
                GC.KeepAlive(certificate);
            }
        }
    }
}
