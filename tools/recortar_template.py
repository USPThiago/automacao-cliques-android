#!/usr/bin/env python3
"""Recorta um template a partir de uma captura de tela do aparelho.

O recorte e salvo sem redimensionamento nem recompressao com perda, porque o
casamento de templates compara os pixels originais. Imprime o retangulo e o
centro do recorte: o centro e o ponto onde o clique sera despachado quando esse
template casar a tela.

Exemplos:
    python tools/recortar_template.py tela.png loja.png --caixa 716 706 885 875
    python tools/recortar_template.py tela.png loja.png --centro 800 790 --tamanho 170
"""

from __future__ import annotations

import argparse
import sys

try:
    from PIL import Image
except ImportError:  # pragma: no cover - depende do ambiente do usuario
    sys.exit("Instale a dependencia com: pip install pillow")


def parse_args() -> argparse.Namespace:
    parser = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    parser.add_argument("entrada", help="captura de tela (adb exec-out screencap -p > tela.png)")
    parser.add_argument("saida", help="arquivo do template, de preferencia .png")
    modo = parser.add_mutually_exclusive_group(required=True)
    modo.add_argument(
        "--caixa",
        nargs=4,
        type=int,
        metavar=("ESQUERDA", "TOPO", "DIREITA", "BAIXO"),
        help="retangulo do recorte em pixels",
    )
    modo.add_argument(
        "--centro",
        nargs=2,
        type=int,
        metavar=("X", "Y"),
        help="centro do recorte em pixels (use com --tamanho)",
    )
    parser.add_argument(
        "--tamanho",
        type=int,
        default=160,
        help="lado do recorte quadrado quando se usa --centro (padrao: 160)",
    )
    return parser.parse_args()


def caixa_do_centro(x: int, y: int, lado: int) -> tuple[int, int, int, int]:
    metade = lado // 2
    return x - metade, y - metade, x - metade + lado, y - metade + lado


def main() -> None:
    args = parse_args()
    with Image.open(args.entrada) as imagem:
        largura, altura = imagem.size
        if args.caixa:
            esquerda, topo, direita, baixo = args.caixa
        else:
            if args.tamanho < 8:
                sys.exit("--tamanho deve ter pelo menos 8 px")
            esquerda, topo, direita, baixo = caixa_do_centro(*args.centro, args.tamanho)

        if direita <= esquerda or baixo <= topo:
            sys.exit("retangulo invalido: direita/baixo devem ser maiores que esquerda/topo")
        if esquerda < 0 or topo < 0 or direita > largura or baixo > altura:
            sys.exit(f"retangulo fora da imagem de {largura}x{altura}")

        recorte = imagem.crop((esquerda, topo, direita, baixo)).convert("RGB")
        recorte.save(args.saida)

    centro_x = (esquerda + direita) / 2
    centro_y = (topo + baixo) / 2
    print(f"tela      {largura}x{altura} ({args.entrada})")
    print(f"recorte   [{esquerda},{topo}][{direita},{baixo}] -> {direita - esquerda}x{baixo - topo} px")
    print(f"centro    ({centro_x}, {centro_y})  <- o clique cai aqui")
    print(f"salvo em  {args.saida}")
    print(f"use na sequencia como: @{args.saida.rsplit('/', 1)[-1].rsplit('.', 1)[0]}")


if __name__ == "__main__":
    main()
