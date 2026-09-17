use haive_bitcos::{encode_container, BitcosContainer, BitcosTensorInput};
use serde::Deserialize;
use std::env;
use std::fs;
use std::path::{Path, PathBuf};

#[derive(Debug, Deserialize)]
struct Manifest {
    tensors: Vec<ManifestTensor>,
}

#[derive(Debug, Deserialize)]
struct ManifestTensor {
    name: String,
    shape: Vec<u64>,
    group_size: u32,
    weights_i8: String,
    #[serde(default)]
    scales_f16_le: Option<String>,
}

fn main() {
    if let Err(error) = run() {
        eprintln!("haive-bitcos: {error}");
        std::process::exit(2);
    }
}

fn run() -> Result<(), Box<dyn std::error::Error>> {
    let mut args = env::args().skip(1);
    match args.next().as_deref() {
        Some("pack") => {
            let manifest = require_arg(args.next(), "manifest.json")?;
            let output = require_arg(args.next(), "output.bitcos")?;
            if args.next().is_some() {
                return Err("pack accepts exactly <manifest.json> <output.bitcos>".into());
            }
            pack(Path::new(&manifest), Path::new(&output))
        }
        Some("inspect") => {
            let input = require_arg(args.next(), "model.bitcos")?;
            if args.next().is_some() {
                return Err("inspect accepts exactly <model.bitcos>".into());
            }
            inspect(Path::new(&input))
        }
        Some("unpack") => {
            let input = require_arg(args.next(), "model.bitcos")?;
            let output_dir = require_arg(args.next(), "output-directory")?;
            if args.next().is_some() {
                return Err("unpack accepts exactly <model.bitcos> <output-directory>".into());
            }
            unpack(Path::new(&input), Path::new(&output_dir))
        }
        _ => {
            eprintln!(
                "Usage:\n  haive-bitcos pack <manifest.json> <output.bitcos>\n  \
                 haive-bitcos inspect <model.bitcos>\n  \
                 haive-bitcos unpack <model.bitcos> <output-directory>"
            );
            Err("missing or unknown command".into())
        }
    }
}

fn require_arg(value: Option<String>, label: &str) -> Result<String, Box<dyn std::error::Error>> {
    value.ok_or_else(|| format!("missing {label}").into())
}

fn pack(manifest_path: &Path, output_path: &Path) -> Result<(), Box<dyn std::error::Error>> {
    let manifest_bytes = fs::read(manifest_path)?;
    let manifest: Manifest = serde_json::from_slice(&manifest_bytes)?;
    if manifest.tensors.is_empty() {
        return Err("manifest must contain at least one tensor".into());
    }

    let base_dir = manifest_path.parent().unwrap_or_else(|| Path::new("."));
    let mut tensors = Vec::with_capacity(manifest.tensors.len());

    for tensor in manifest.tensors {
        let weight_path = resolve(base_dir, &tensor.weights_i8);
        let raw_weights = fs::read(&weight_path)?;
        let weights = raw_weights
            .into_iter()
            .map(|value| match value {
                0 => Ok(0i8),
                1 => Ok(1i8),
                255 => Ok(-1i8),
                other => Err(format!(
                    "{} contains byte {other}; raw ternary files must contain signed i8 values -1/0/+1",
                    weight_path.display()
                )),
            })
            .collect::<Result<Vec<_>, _>>()?;

        let scales_f16_le = match tensor.scales_f16_le {
            Some(path) => fs::read(resolve(base_dir, &path))?,
            None => Vec::new(),
        };

        tensors.push(BitcosTensorInput {
            name: tensor.name,
            shape: tensor.shape,
            group_size: tensor.group_size,
            weights,
            scales_f16_le,
        });
    }

    let encoded = encode_container(&tensors)?;
    fs::write(output_path, &encoded)?;
    println!(
        "wrote {} tensors to {} ({} bytes)",
        tensors.len(),
        output_path.display(),
        encoded.len(),
    );
    inspect_bytes(&encoded)?;
    Ok(())
}

fn inspect(input_path: &Path) -> Result<(), Box<dyn std::error::Error>> {
    let bytes = fs::read(input_path)?;
    println!("{}: {} bytes", input_path.display(), bytes.len());
    inspect_bytes(&bytes)
}

fn inspect_bytes(bytes: &[u8]) -> Result<(), Box<dyn std::error::Error>> {
    let container = BitcosContainer::parse(bytes)?;
    let mut total_elements = 0u64;
    let mut total_nonzero = 0u64;

    for tensor in &container.tensors {
        total_elements += tensor.metadata.element_count;
        total_nonzero += tensor.metadata.nonzero_count;
        println!(
            "{} shape={:?} group={} nnz={}/{} zero_density={:.4} symbol_bpw={:.4} scales={}B",
            tensor.metadata.name,
            tensor.metadata.shape,
            tensor.metadata.group_size,
            tensor.metadata.nonzero_count,
            tensor.metadata.element_count,
            tensor.zero_density(),
            tensor.effective_bits_per_weight(),
            tensor.scales.len(),
        );
    }

    let zero_density = if total_elements == 0 {
        0.0
    } else {
        1.0 - total_nonzero as f64 / total_elements as f64
    };
    let symbol_bpw = if total_elements == 0 {
        0.0
    } else {
        (total_elements + total_nonzero) as f64 / total_elements as f64
    };
    println!(
        "total tensors={} elements={} zero_density={:.4} symbol_bpw={:.4}",
        container.tensors.len(),
        total_elements,
        zero_density,
        symbol_bpw,
    );
    Ok(())
}

fn unpack(input_path: &Path, output_dir: &Path) -> Result<(), Box<dyn std::error::Error>> {
    let bytes = fs::read(input_path)?;
    let container = BitcosContainer::parse(&bytes)?;
    fs::create_dir_all(output_dir)?;

    for tensor in container.tensors {
        let safe_name = tensor
            .metadata
            .name
            .chars()
            .map(|ch| if ch.is_ascii_alphanumeric() || matches!(ch, '.' | '-' | '_') { ch } else { '_' })
            .collect::<String>();
        let weights = tensor
            .decode()?
            .into_iter()
            .map(|value| value as u8)
            .collect::<Vec<_>>();
        fs::write(output_dir.join(format!("{safe_name}.i8")), weights)?;
        if !tensor.scales.is_empty() {
            fs::write(
                output_dir.join(format!("{safe_name}.scales-f16le")),
                tensor.scales,
            )?;
        }
    }
    Ok(())
}

fn resolve(base: &Path, value: &str) -> PathBuf {
    let path = Path::new(value);
    if path.is_absolute() {
        path.to_owned()
    } else {
        base.join(path)
    }
}
